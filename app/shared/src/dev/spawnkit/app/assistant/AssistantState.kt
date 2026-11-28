package dev.spawnkit.app.assistant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.spawnkit.ai.AgentEvent
import dev.spawnkit.ai.AgentMessage
import dev.spawnkit.ai.AgentModel
import dev.spawnkit.ai.AgentOutcome
import dev.spawnkit.ai.AgentSession
import dev.spawnkit.ai.AgentToolbox
import dev.spawnkit.ai.CharacterMaker
import dev.spawnkit.ai.CharacterResult
import dev.spawnkit.ai.Cost
import dev.spawnkit.ai.DemoModel
import dev.spawnkit.ai.KoogModel
import dev.spawnkit.ai.WholeGameReplay
import dev.spawnkit.app.editor.EditorState
import dev.spawnkit.app.images.PickedImage
import dev.spawnkit.editing.AcceptResult
import dev.spawnkit.editing.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/** SpawnKit AI for the signed-in account (C11b): [key] names the server and account, so a new one starts afresh. */
class HostedChoice(
    val key: String,
    val model: AgentModel,
)

/**
 * Character Forge for the signed-in account (ADR-0013), as the assistant uses it: the builder passes the account's
 * forge, which strips the photo's metadata before it leaves the device.
 */
fun interface PhotoForge {
    suspend fun make(
        png: ByteArray,
        styles: List<String>,
        consent: Boolean,
    ): CharacterResult
}

/** One line of the assistant's conversation (C13b), oldest first in [AssistantState.chat]. */
sealed class ChatEntry {
    /** The words shown. */
    abstract val text: String

    /** What the creator asked, or how they answered a question. */
    class Request(
        override val text: String,
    ) : ChatEntry()

    /** The assistant's answer or question, without changes to the game. */
    class Reply(
        override val text: String,
    ) : ChatEntry()

    /**
     * Changes the assistant made, applied as one undo step labelled [label]: [text] is its summary and [steps] the
     * changes in plain words. [AssistantState.revert] undoes it while it is still the latest step. [replay] marks changes
     * from the labelled whole-game replay, where no model was called.
     */
    class Applied(
        override val text: String,
        val steps: List<String>,
        val label: String,
        internal val generation: Int,
        internal val after: Project,
        val replay: Boolean = false,
    ) : ChatEntry() {
        /** Whether the creator used Revert on it; see [AssistantState.isReverted] for whether it is still undone. */
        var reverted by mutableStateOf(false)
            internal set
    }

    /** Something that happened along the way, such as a rejected proposal. */
    class Note(
        override val text: String,
    ) : ChatEntry()
}

/**
 * The builder's assistant (C7c, C13b): one [AgentSession] over the game in [editor], shown as a conversation in [chat].
 *
 * AI-first (C13b): a proposal is applied as soon as it arrives, as one undo step the creator can [revert]. With
 * [reviewFirst], it waits for [accept] or [reject] instead. [cancel] and failures leave the game unchanged.
 *
 * Which model answers (C11b, ADR-0011): the creator's own key when switched on in Developer settings, otherwise SpawnKit
 * AI through [hosted] when signed in to a server that offers it, otherwise the labelled [DemoModel]. The key lives only
 * in [secrets] and is handed only to [liveModel].
 *
 * Character Forge (C16b): the creator can attach a [photo]. The model is told only that one is attached; when it asks
 * to make characters, the assistant waits for [confirm] with consent, or [decline], before anything is sent through
 * [forge] (`null` when not signed in to a server that offers it).
 */
class AssistantState(
    private val editor: EditorState,
    private val secrets: SecretStore,
    private val scope: CoroutineScope,
    private val liveModel: (String) -> AgentModel = { KoogModel.anthropic(it) },
    private val hosted: () -> HostedChoice? = { null },
    private val forge: () -> PhotoForge? = { null },
) {
    /** The creator's request being typed; it empties once sent. */
    var prompt by mutableStateOf("")

    /** Whether proposals wait for the creator's Accept instead of being applied right away (C13b). */
    var reviewFirst by mutableStateOf(false)

    /** The conversation so far; cleared when another project is loaded, kept when the scene changes (C13b). */
    val chat = mutableStateListOf<ChatEntry>()

    private var chatFor = editor.projectGeneration

    var running by mutableStateOf(false)
        private set

    /** What the assistant did so far for the current request, in plain words. */
    val progress = mutableStateListOf<String>()

    var outcome by mutableStateOf<AgentOutcome?>(null)
        private set

    /** The photo attached for Character Forge; it is never shown to the model and is let go once sent. */
    var photo by mutableStateOf<PickedImage?>(null)
        private set

    /** Cancellations, refusals and failures, in plain words. */
    var notice by mutableStateOf<String?>(null)
        private set

    var hasKey by mutableStateOf(secrets.read(KEY_NAME) != null)
        private set

    private var live by mutableStateOf(hasKey)

    /** Whether the live model answers; only possible with a saved key. */
    var useLive: Boolean
        get() = live && hasKey
        set(value) {
            if (value == live || (value && !hasKey)) return
            live = value
            reset(null)
        }

    /** Where the key is kept, for example "Android Keystore". */
    val keyLocation: String get() = secrets.where

    /** Whether the key survives closing SpawnKit. */
    val keyPersists: Boolean get() = secrets.persistent

    private var session: AgentSession? = null
    private var sessionFor: Triple<String, Int, String>? = null
    private var model: AgentModel? = null
    private var modelFor: String? = null
    private var job: Job? = null

    /** The whole-game replay while it plays (C13b), with a key of its own so each replay starts afresh. */
    private var replaying: AgentModel? = null
    private var replayKey = ""
    private var replays = 0

    /** The attached photo as the agent's `make_character` sees it: whether there is one, and sending it on confirmation. */
    private val maker =
        object : CharacterMaker {
            override val photoAttached: Boolean get() = photo != null

            override fun unavailable(): String? =
                when {
                    photo == null -> NO_PHOTO
                    forge() == null -> NO_FORGE
                    else -> null
                }

            override suspend fun make(
                styles: List<String>,
                consent: Boolean,
            ): CharacterResult {
                val png = photo?.png ?: error(NO_PHOTO)
                val made = (forge() ?: error(NO_FORGE)).make(png, styles, consent)
                photo = null
                imagesMade += made.images.size
                return made
            }
        }

    /** Whether the scripted demo answers, so its example prompts are worth showing. */
    val isDemo: Boolean get() = modelKey() == DEMO

    /** Whether signing in would bring SpawnKit AI: nothing but the demo answers now. */
    val needsSignIn: Boolean get() = isDemo

    /** Label of the model that answers: live, or the scripted demo. */
    val modelLabel: String get() = runCatching { currentModel().info.label }.getOrElse { it.message ?: "The live model is unavailable" }

    /** Character images made in this conversation; they use the account's SpawnKit AI allowance. */
    var imagesMade by mutableStateOf(0)
        private set

    /** Tokens and estimated price so far, or that nothing is paid, and any character images made. */
    val costText: String
        get() {
            val live = runCatching { currentModel().info.live }.getOrDefault(false)
            if (imagesMade == 0) return costText(live, session?.cost)
            val text = if (live) costText(true, session?.cost) else "Scripted text (no cost)"
            return "$text · $imagesMade character images from your SpawnKit AI allowance"
        }

    /** The conversation, for a details view. */
    val transcript: List<AgentMessage> get() = session?.transcript.orEmpty()

    /** Whether the game is playing; the assistant only works on a stopped game. */
    val playing: Boolean get() = editor.play != null

    /** Changes when another project is loaded or the scene changes; see [refresh]. */
    val gameKey: Pair<Int, String> get() = editor.projectGeneration to editor.sceneId

    fun canAsk(): Boolean = !running && !playing && prompt.isNotBlank()

    /** Drops a proposal made for a project or scene that is no longer open, with a notice. */
    fun refresh() {
        dropIfStale()
        clearChatIfNewProject()
    }

    /**
     * Whether [entry] can still be reverted on its own: it is the editor's latest step in this project, and the game is
     * stopped. After a manual change or an Undo it cannot, so Revert never removes the creator's own work.
     */
    fun canRevert(entry: ChatEntry.Applied): Boolean {
        editor.revision // recompose when the game changes
        return !playing &&
            entry.generation == editor.projectGeneration &&
            editor.editor.project === entry.after &&
            editor.editor.undoLabel == entry.label
    }

    /** Whether [entry] was reverted and is still undone; Redo brings it back, and then it can be reverted again. */
    fun isReverted(entry: ChatEntry.Applied): Boolean {
        editor.revision // recompose when the game changes
        return entry.reverted && editor.editor.project !== entry.after
    }

    /** Undoes the assistant's step [entry] when [canRevert]; otherwise explains how to step back with Undo. */
    fun revert(entry: ChatEntry.Applied) {
        if (!canRevert(entry)) {
            if (!isReverted(entry)) notice = if (playing) STOP_TO_REVERT else CANNOT_REVERT
            return
        }
        editor.undo()
        entry.reverted = true
        notice = REVERTED
        session?.takeIf { sessionFor?.second == entry.generation }?.reverted()
    }

    fun saveKey(key: String) {
        require(key.isNotBlank()) { "Enter a key" }
        secrets.write(KEY_NAME, key.trim())
        hasKey = true
        live = true
        reset(null)
    }

    fun removeKey() {
        secrets.delete(KEY_NAME)
        hasKey = false
        live = false
        reset(null)
    }

    /** Starts the request in [prompt]; any open proposal is dropped first. */
    fun ask() {
        if (!canAsk()) return
        val request = prompt.trim()
        clearChatIfNewProject()
        if (outcome is AgentOutcome.Proposal) chat += ChatEntry.Note(SUPERSEDED)
        chat += ChatEntry.Request(request)
        prompt = ""
        start { session, onEvent -> session.ask(request, onEvent) }
    }

    /** Answers the assistant's question and continues the same proposal. */
    fun answer(text: String) {
        if (dropIfStale() || running || outcome !is AgentOutcome.Question || text.isBlank()) return
        chat += ChatEntry.Request(text.trim())
        start(keepProgress = true) { session, onEvent -> session.answer(text.trim(), onEvent) }
    }

    /** Attaches [picked] for Character Forge, replacing any earlier photo. */
    fun attach(picked: PickedImage) {
        photo = picked
    }

    fun removePhoto() {
        photo = null
    }

    /** The creator agreed to send the photo; [consent] must be ticked ("This is me, or this person agreed"). */
    fun confirm(consent: Boolean) {
        if (!consent || dropIfStale() || running || outcome !is AgentOutcome.Confirmation) return
        chat += ChatEntry.Note("You sent your photo to the image model.")
        start(keepProgress = true) { session, onEvent -> session.confirm(consent, onEvent) }
    }

    /** The creator chose not to send the photo; the assistant goes on without it. */
    fun decline() {
        if (dropIfStale() || running || outcome !is AgentOutcome.Confirmation) return
        chat += ChatEntry.Note("You chose not to send your photo. Nothing was sent.")
        start(keepProgress = true) { session, onEvent -> session.decline(onEvent) }
    }

    /** Applies the proposal waiting for review as one undoable step. */
    fun accept() {
        if (dropIfStale()) return
        val proposal = outcome as? AgentOutcome.Proposal ?: return
        val active = session ?: return
        apply(active, proposal)
    }

    fun reject() {
        val rejected = outcome is AgentOutcome.Proposal
        session?.reject()
        outcome = null
        notice = null
        progress.clear()
        if (rejected) chat += ChatEntry.Note("You rejected the changes. Nothing in your game changed.")
    }

    /** Applies [proposal] as one undo step and adds it to the chat; a refusal leaves the game unchanged. */
    private fun apply(
        active: AgentSession,
        proposal: AgentOutcome.Proposal,
    ) {
        val label = "Assistant: " + proposal.summary.take(LABEL_LENGTH)
        when (val result = active.accept(label)) {
            is AcceptResult.Accepted -> {
                outcome = null
                notice = null
                progress.clear()
                chat +=
                    ChatEntry.Applied(
                        proposal.summary,
                        proposal.steps,
                        label,
                        editor.projectGeneration,
                        editor.editor.project,
                        replay = replaying != null,
                    )
            }

            is AcceptResult.Refused -> {
                // Applied right away, nothing waits for review: let the session go on from the current game.
                if (outcome == null) active.reject()
                notice = result.message
            }
        }
    }

    /** Shows [result]: a proposal is applied unless [reviewFirst]; answers and questions join the chat. */
    private fun settle(
        active: AgentSession,
        result: AgentOutcome,
    ) {
        outcome = null
        notice = null
        when (result) {
            is AgentOutcome.Proposal -> {
                if (reviewFirst) outcome = result else apply(active, result)
            }

            is AgentOutcome.Answer -> {
                chat += ChatEntry.Reply(result.text)
                progress.clear()
            }

            is AgentOutcome.Question -> {
                chat += ChatEntry.Reply(result.question)
                outcome = result
            }

            is AgentOutcome.Confirmation -> {
                outcome = result
            }

            is AgentOutcome.Failed -> {
                notice = result.message
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /**
     * Plays the reviewed whole-game recording ([WholeGameReplay]) on the open game, for creators who are not signed in
     * (C13b). It is labelled as a replay throughout, calls no model, and is applied like any answer, with Revert. Start it
     * on a new blank game, where the recorded IDs exist; [pauseMillis] paces the steps so creators can follow them.
     */
    fun watchReplay(pauseMillis: Long = REPLAY_PAUSE_MILLIS) {
        if (running || playing) return
        clearChatIfNewProject()
        replaying = WholeGameReplay.model(pauseMillis)
        replayKey = "$REPLAY${++replays}"
        val request = WholeGameReplay.prompt
        chat += ChatEntry.Note(REPLAY_NOTE.replace("DATE", WholeGameReplay.recording.recordedOn))
        chat += ChatEntry.Request(request)
        start(after = { replaying = null }) { session, onEvent -> session.ask(request, onEvent) }
    }

    private fun start(
        keepProgress: Boolean = false,
        after: () -> Unit = {},
        work: suspend (AgentSession, (AgentEvent) -> Unit) -> AgentOutcome,
    ) {
        dropIfStale()
        val active =
            sessionNow() ?: run {
                after()
                return
            }
        if (!keepProgress) progress.clear()
        outcome = null
        notice = null
        running = true
        job =
            scope.launch {
                try {
                    settle(active, work(active, ::record))
                } catch (cancelled: CancellationException) {
                    outcome = null
                    notice = CANCELLED
                    throw cancelled
                } catch (problem: Exception) {
                    // Never let an unexpected failure crash the builder; the session's changes are not applied.
                    active.reject()
                    outcome = null
                    notice = "The assistant stopped: ${problem.message ?: "unknown problem"}. Nothing in your game changed."
                } finally {
                    running = false
                    job = null
                    after()
                }
            }
    }

    private fun record(event: AgentEvent) {
        if (event !is AgentEvent.UsedTool) return
        val line =
            when {
                event.name == AgentToolbox.MAKE_CHARACTER && event.staged != null -> "Made characters from your photo"
                event.name == AgentToolbox.MAKE_CHARACTER && event.refused -> "No characters were made"
                event.name == AgentToolbox.MAKE_CHARACTER -> "Asked to send your photo to the image model"
                event.refused -> "A step was refused (${event.name})"
                event.staged != null -> "Staged: ${event.staged}"
                event.name == AgentToolbox.LOOK -> "Looked at the game"
                event.name == AgentToolbox.COMPONENTS -> "Read the available components"
                event.name == AgentToolbox.CHECK -> "Checked the game"
                event.name == AgentToolbox.TRACE -> "Played the game to see which rules ran"
                event.name == AgentToolbox.ASK || event.name == AgentToolbox.FINISH -> null
                else -> "Used ${event.name}"
            }
        line?.let(progress::add)
    }

    /** The session for the current model, scene and project; a new one when any of them changed. */
    private fun sessionNow(): AgentSession? {
        val key = Triple(modelKey(), editor.projectGeneration, editor.sceneId)
        if (session != null && sessionFor == key) return session
        session?.reject()
        val chosen =
            runCatching { currentModel() }.getOrElse { problem ->
                notice = problem.message ?: "The assistant could not start."
                return null
            }
        sessionFor = key
        return AgentSession(editor.engine, editor.editor, editor.sceneId, chosen, characters = maker).also { session = it }
    }

    /** Drops a proposal or question whose project or scene is gone; `true` when one was dropped. */
    private fun dropIfStale(): Boolean {
        val made = sessionFor ?: return false
        if (made.second == editor.projectGeneration && made.third == editor.sceneId) return false
        val dropped =
            when (outcome) {
                is AgentOutcome.Proposal -> DROPPED
                is AgentOutcome.Question -> DROPPED_QUESTION
                is AgentOutcome.Confirmation -> DROPPED_PHOTO
                else -> null
            }
        reset(dropped)
        return dropped != null
    }

    /** Clears the conversation when another project was loaded since it started. */
    private fun clearChatIfNewProject() {
        if (chatFor == editor.projectGeneration) return
        chatFor = editor.projectGeneration
        chat.clear()
    }

    private fun reset(message: String?) {
        clearChatIfNewProject()
        // Another scene (or model) in the same project: the conversation stays on screen, but the model starts afresh.
        if (session != null && chat.isNotEmpty() && sessionFor?.second == editor.projectGeneration) {
            chat += ChatEntry.Note(FRESH_START)
        }
        job?.cancel()
        session?.reject()
        session = null
        sessionFor = null
        model = null
        outcome = null
        imagesMade = 0
        progress.clear()
        notice = message
    }

    /** Which model answers now: the creator's own key, SpawnKit AI for an account, or the demo. */
    private fun modelKey(): String =
        when {
            replaying != null -> replayKey
            useLive && secrets.read(KEY_NAME) != null -> OWN_KEY
            else -> hosted()?.let { HOSTED + it.key } ?: DEMO
        }

    /** The model for the current choice, made once per choice; live models get the key and nothing else does. */
    private fun currentModel(): AgentModel {
        val key = modelKey()
        replaying?.takeIf { key == replayKey }?.let { return it }
        model?.takeIf { modelFor == key }?.let { return it }
        val made =
            when {
                key == OWN_KEY -> liveModel(secrets.read(KEY_NAME) ?: error("The key was removed"))
                key.startsWith(HOSTED) -> hosted()?.model ?: DemoModel()
                else -> DemoModel()
            }
        model = made
        modelFor = key
        return made
    }

    companion object {
        /** Secret name of the Anthropic API key. */
        const val KEY_NAME = "anthropic-api-key"

        private const val LABEL_LENGTH = 60
        private const val OWN_KEY = "own-key"
        private const val HOSTED = "hosted:"
        private const val DEMO = "demo"
        private const val REPLAY = "replay:"
        private const val REPLAY_PAUSE_MILLIS = 350L
        private const val REPLAY_NOTE =
            "Replay of a recorded session: SpawnKit AI built this game from the prompt below on DATE. " +
                "No model is called now; every step still runs in the real builder."
        private const val CANCELLED = "Cancelled. Nothing in your game changed."
        private const val REVERTED = "Reverted. Your game is back as it was before."
        private const val CANNOT_REVERT =
            "You changed the game after this, so it can't be reverted on its own. Use Undo (↶) to step back."
        private const val STOP_TO_REVERT = "Stop the game to revert."
        private const val SUPERSEDED = "The changes waiting for review were dropped. Nothing was applied."
        private const val FRESH_START = "The assistant starts a fresh conversation here. It still sees every scene of your game."
        private const val DROPPED = "Your game changed, so the proposal was dropped. Nothing was applied."
        private const val DROPPED_QUESTION = "Your game changed, so the question was dropped. Nothing was applied."
        private const val DROPPED_PHOTO = "Your game changed, so the request to send your photo was dropped. Nothing was sent."
        private const val NO_PHOTO = "Attach a photo in the chat first (Attach photo, under the prompt)."
        private const val NO_FORGE =
            "Making characters from photos needs you to sign in to a SpawnKit server that offers it (Project → Account)."

        /** Plain cost text for [cost] from a [live] model or not. */
        internal fun costText(
            live: Boolean,
            cost: Cost?,
        ): String {
            if (!live) return "No cost (no model is called)"
            if (cost == null) return "0 in · 0 out tokens"
            val tokens = "${cost.usage.inputTokens} in · ${cost.usage.outputTokens} out tokens"
            val dollars = cost.dollars ?: return tokens
            val cents = (dollars * 100).roundToLong()
            return "$tokens · about $${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
        }
    }
}
