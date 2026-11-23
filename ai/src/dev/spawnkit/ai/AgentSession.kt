package dev.spawnkit.ai

import dev.spawnkit.editing.AcceptResult
import dev.spawnkit.editing.Editor
import dev.spawnkit.editing.StagedChanges
import dev.spawnkit.gameplay.StandardEngine

/** Progress of a running session, for the assistant's progress display. */
public sealed interface AgentEvent {
    /** The model is being asked (step [step], counted from 1). */
    public data class Thinking(
        /** Step number. */
        public val step: Int,
    ) : AgentEvent

    /** A tool ran. [staged] is the review label of a staged change, or `null` for reading tools and refusals. */
    public data class UsedTool(
        /** Tool name. */
        public val name: String,
        /** Label of the staged change, or `null`. */
        public val staged: String?,
        /** Whether the call was refused. */
        public val refused: Boolean,
    ) : AgentEvent
}

/** Where a session stopped. */
public sealed interface AgentOutcome {
    /** Cost so far. */
    public val cost: Cost

    /** Proposed changes for review; nothing changed in the game yet. Accept or reject them on the session. */
    public data class Proposal(
        /** Summary in the creator's words. */
        public val summary: String,
        /** Review labels of the staged steps. */
        public val steps: List<String>,
        /** The staged changes, for previews. */
        public val changes: StagedChanges,
        override val cost: Cost,
    ) : AgentOutcome

    /** An answer without changes, for example an explanation of a rule. */
    public data class Answer(
        /** The answer. */
        public val text: String,
        override val cost: Cost,
    ) : AgentOutcome

    /** The agent needs the creator to choose before going on; reply with [AgentSession.answer]. */
    public data class Question(
        /** The question. */
        public val question: String,
        /** Suggested answers, possibly empty. */
        public val options: List<String>,
        override val cost: Cost,
    ) : AgentOutcome

    /**
     * The agent wants to send the creator's photo to the image model to make characters in [styles] (C16b, ADR-0013).
     * Nothing has left the device: answer with [AgentSession.confirm] (with the creator's consent) or
     * [AgentSession.decline].
     */
    public data class Confirmation(
        /** Style IDs that would be made, in order. */
        public val styles: List<String>,
        /** The question for the creator, in plain words. */
        public val message: String,
        override val cost: Cost,
    ) : AgentOutcome

    /** The session could not finish; staged changes were discarded. */
    public data class Failed(
        /** Plain-language reason. */
        public val message: String,
        override val cost: Cost,
    ) : AgentOutcome
}

/**
 * One assistant conversation about the game in [editor] (ADR-0005, ADR-0009). Each prompt stages changes through the
 * [AgentToolbox] against the current workspace revision; the creator then accepts them as one undoable step or rejects
 * them. Cancelling the coroutine that runs [ask], [answer] or [confirm] stops the session and leaves the game unchanged.
 *
 * [characters] makes characters from the photo the creator attached (C16b); without it `make_character` is refused.
 * The agent can only ask to use it: the photo is sent when the creator calls [confirm], never before.
 *
 * Use it from one thread (the UI thread in the builder).
 */
public class AgentSession(
    private val engine: StandardEngine,
    private val editor: Editor,
    private val sceneId: String,
    private val model: AgentModel,
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
    private val characters: CharacterMaker? = null,
) {
    private val messages = mutableListOf<AgentMessage>()
    private var staged: StagedChanges? = null
    private var toolbox: AgentToolbox? = null
    private var waiting: Waiting? = null
    private var confirming: Confirming? = null
    private var usage = Usage()

    /** The conversation so far, including tool results, for the assistant's details view. */
    public val transcript: List<AgentMessage> get() = messages.toList()

    /** The model answering, shown with its label (live or replay). */
    public val modelInfo: ModelInfo get() = model.info

    /** Cost of the whole conversation so far. */
    public val cost: Cost get() = Cost.of(usage, model.info)

    /** The open proposal, or `null`. */
    public val changes: StagedChanges? get() = staged?.takeUnless { it.closed }

    /** Starts a new request; an open proposal is discarded first. */
    public suspend fun ask(
        prompt: String,
        onEvent: (AgentEvent) -> Unit = {},
    ): AgentOutcome {
        require(prompt.isNotBlank()) { "Ask something" }
        reject()
        val changes = StagedChanges(editor)
        staged = changes
        toolbox = AgentToolbox(engine, changes, sceneId, characters)
        waiting = null
        confirming = null
        messages += AgentMessage.User(if (characters?.photoAttached == true) "$prompt\n\n$PHOTO_ATTACHED" else prompt)
        return run(onEvent)
    }

    /** Answers the agent's clarifying question and continues the same proposal. */
    public suspend fun answer(
        text: String,
        onEvent: (AgentEvent) -> Unit = {},
    ): AgentOutcome {
        val pending = checkNotNull(waiting) { "There is no question to answer" }
        waiting = null
        val replies =
            pending.replies + ToolReply(pending.call.id, pending.call.name, "The creator answered: $text") +
                pending.skipped.map { ToolReply(it.id, it.name, NOT_RUN) }
        messages += AgentMessage.ToolReplies(replies)
        return run(onEvent)
    }

    /**
     * The creator agreed to send the photo: makes the characters the agent asked for and stages them, then continues.
     * [consent] must be true: the creator confirmed that the photo shows them or someone who agreed. Refusals and
     * failures are told to the agent, and nothing is staged for them.
     */
    public suspend fun confirm(
        consent: Boolean,
        onEvent: (AgentEvent) -> Unit = {},
    ): AgentOutcome {
        require(consent) { "Confirm that the photo shows you or someone who agreed" }
        val pending = checkNotNull(confirming) { "Nothing is waiting for confirmation" }
        val maker = checkNotNull(characters)
        val tools = checkNotNull(toolbox)
        confirming = null
        val outcome =
            try {
                tools.stageCharacter(pending.request, maker.make(pending.request.styles, consent))
            } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
                staged?.discard()
                throw cancelled
            } catch (problem: Exception) {
                tools.refusal("No character could be made: ${problem.message ?: "unknown problem"}")
            }
        onEvent(AgentEvent.UsedTool(pending.call.name, (outcome as? ToolOutcome.Staged)?.label, outcome is ToolOutcome.Refused))
        return resume(pending, outcome.text, onEvent)
    }

    /** The creator chose not to send the photo: the agent is told, nothing is sent, and the session continues. */
    public suspend fun decline(onEvent: (AgentEvent) -> Unit = {}): AgentOutcome {
        val pending = checkNotNull(confirming) { "Nothing is waiting for confirmation" }
        confirming = null
        val text = checkNotNull(toolbox).refusal(DECLINED).text
        onEvent(AgentEvent.UsedTool(pending.call.name, null, refused = true))
        return resume(pending, text, onEvent)
    }

    private suspend fun resume(
        pending: Confirming,
        text: String,
        onEvent: (AgentEvent) -> Unit,
    ): AgentOutcome {
        val replies =
            pending.replies + ToolReply(pending.call.id, pending.call.name, text) +
                pending.skipped.map { ToolReply(it.id, it.name, NOT_RUN) }
        messages += AgentMessage.ToolReplies(replies)
        return run(onEvent)
    }

    /** Accepts the open proposal as one undoable step labelled [label]. */
    public fun accept(label: String): AcceptResult {
        val changes = changes ?: return AcceptResult.Refused("There is nothing to accept")
        val result = changes.accept(label)
        messages += AgentMessage.User(if (result is AcceptResult.Accepted) "(The creator accepted the changes.)" else "(Accepting failed.)")
        return result
    }

    /** Discards the open proposal; the game is unchanged. */
    public fun reject() {
        val changes = changes ?: return
        changes.discard()
        waiting = null
        confirming = null
        messages += AgentMessage.User("(The creator rejected the proposed changes; they were discarded.)")
    }

    private suspend fun run(onEvent: (AgentEvent) -> Unit): AgentOutcome {
        val changes = checkNotNull(staged)
        val tools = checkNotNull(toolbox)
        var step = 0
        while (step < maxSteps) {
            step++
            onEvent(AgentEvent.Thinking(step))
            val reply =
                try {
                    model.reply(ModelRequest(SYSTEM, messages.toList(), tools.tools))
                } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
                    changes.discard()
                    throw cancelled
                } catch (problem: Exception) {
                    changes.discard()
                    return AgentOutcome.Failed("The assistant could not answer: ${problem.message ?: "unknown problem"}", cost)
                }
            usage += reply.usage
            messages += AgentMessage.Assistant(reply.text, reply.calls)
            if (reply.calls.isEmpty()) return conclude(reply.text.orEmpty().ifBlank { "Done." })
            val replies = mutableListOf<ToolReply>()
            for ((index, call) in reply.calls.withIndex()) {
                val outcome = tools.call(call.name, call.input)
                onEvent(AgentEvent.UsedTool(call.name, (outcome as? ToolOutcome.Staged)?.label, outcome is ToolOutcome.Refused))
                when (outcome) {
                    is ToolOutcome.Question -> {
                        waiting = Waiting(replies.toList(), call, reply.calls.drop(index + 1))
                        return AgentOutcome.Question(outcome.question, outcome.options, cost)
                    }

                    is ToolOutcome.Confirm -> {
                        confirming = Confirming(replies.toList(), call, reply.calls.drop(index + 1), outcome.request)
                        val styles = CharacterRecipe.describe(outcome.request.styles)
                        return AgentOutcome.Confirmation(
                            outcome.request.styles,
                            "Send your photo to the image model to make $styles?",
                            cost,
                        )
                    }

                    is ToolOutcome.Finished -> {
                        reply.calls.drop(index + 1).forEach { replies += ToolReply(it.id, it.name, NOT_RUN) }
                        messages += AgentMessage.ToolReplies(replies + ToolReply(call.id, call.name, outcome.text))
                        return conclude(outcome.summary)
                    }

                    else -> {
                        replies += ToolReply(call.id, call.name, outcome.text)
                    }
                }
            }
            messages += AgentMessage.ToolReplies(replies)
        }
        if (changes.operations.isEmpty()) {
            changes.discard()
            return AgentOutcome.Failed("The assistant did not finish within $maxSteps steps", cost)
        }
        return AgentOutcome.Proposal("The assistant ran out of steps; review what it prepared.", changes.labels, changes, cost)
    }

    private fun conclude(summary: String): AgentOutcome {
        val changes = checkNotNull(staged)
        if (changes.operations.isEmpty()) {
            changes.discard()
            return AgentOutcome.Answer(summary, cost)
        }
        return AgentOutcome.Proposal(summary, changes.labels, changes, cost)
    }

    private class Waiting(
        val replies: List<ToolReply>,
        val call: ToolCall,
        val skipped: List<ToolCall>,
    )

    private class Confirming(
        val replies: List<ToolReply>,
        val call: ToolCall,
        val skipped: List<ToolCall>,
        val request: CharacterRequest,
    )

    /** Session defaults. */
    public companion object {
        /** Model calls allowed per request. */
        public const val DEFAULT_MAX_STEPS: Int = 30

        private const val NOT_RUN = "Not run: the session paused for the creator."
        private const val DECLINED = "The creator chose not to send the photo, so nothing was sent and no characters were made."

        /** Added to the creator's prompt when a photo is attached; the model never receives the photo itself. */
        public const val PHOTO_ATTACHED: String =
            "(The creator attached a photo to this message. You cannot see it; make_character can turn it into characters.)"

        /** Instructions given to every model. */
        public val SYSTEM: String =
            """
            You help a creator build a 2D game in the SpawnKit builder. The creator may not be a programmer.
            - Start with look_at_game to learn the scene ID, object IDs, components and rules. Never invent IDs.
            - Change the game only with the editing tools; they are the builder's own operations. Every change is
              staged: nothing reaches the game until the creator reviews and accepts it, and they can reject it.
            - Prefer When / If / Do rule blocks. Rule and block numbers start at 1, as the builder shows them.
            - To change many objects (for example every coin), call the tool once per object.
            - If the request is ambiguous (for example which of two scores), call ask_creator instead of guessing.
            - Test rule changes with trace_rules and check_game before you finish. To explain why a rule did not
              fire, trace it and report what the trace shows.
            - Finish with finish and a short summary in plain words, without technical IDs.
            - When the creator asks for a character from an attached photo, call make_character. You never see the
              photo, and the creator confirms before it is sent. Then arrange or extend the Character if asked.
            - You cannot publish, export or delete saved files; the creator does that in the builder.
            """.trimIndent()
    }
}
