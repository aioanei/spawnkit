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
import dev.spawnkit.ai.Cost
import dev.spawnkit.ai.DemoModel
import dev.spawnkit.ai.KoogModel
import dev.spawnkit.app.editor.EditorState
import dev.spawnkit.editing.AcceptResult
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
 * The builder's assistant (C7c): one [AgentSession] over the game in [editor], with the creator's review in between.
 * Nothing reaches the game until [accept]; [reject], [cancel] and failures leave it unchanged.
 *
 * Which model answers (C11b, ADR-0011): the creator's own key when switched on in Developer settings, otherwise SpawnKit
 * AI through [hosted] when signed in to a server that offers it, otherwise the labelled [DemoModel]. The key lives only
 * in [secrets] and is handed only to [liveModel].
 */
class AssistantState(
    private val editor: EditorState,
    private val secrets: SecretStore,
    private val scope: CoroutineScope,
    private val liveModel: (String) -> AgentModel = { KoogModel.anthropic(it) },
    private val hosted: () -> HostedChoice? = { null },
) {
    /** The creator's request being typed. */
    var prompt by mutableStateOf("")

    var running by mutableStateOf(false)
        private set

    /** What the assistant did so far for the current request, in plain words. */
    val progress = mutableStateListOf<String>()

    var outcome by mutableStateOf<AgentOutcome?>(null)
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

    /** Whether the scripted demo answers, so its example prompts are worth showing. */
    val isDemo: Boolean get() = modelKey() == DEMO

    /** Whether signing in would bring SpawnKit AI: nothing but the demo answers now. */
    val needsSignIn: Boolean get() = isDemo

    /** Label of the model that answers: live, or the scripted demo. */
    val modelLabel: String get() = runCatching { currentModel().info.label }.getOrElse { it.message ?: "The live model is unavailable" }

    /** Tokens and estimated price so far, or that nothing is paid. */
    val costText: String get() = costText(runCatching { currentModel().info.live }.getOrDefault(false), session?.cost)

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
        start { session, onEvent -> session.ask(request, onEvent) }
    }

    /** Answers the assistant's question and continues the same proposal. */
    fun answer(text: String) {
        if (dropIfStale() || running || outcome !is AgentOutcome.Question || text.isBlank()) return
        start(keepProgress = true) { session, onEvent -> session.answer(text.trim(), onEvent) }
    }

    /** Applies the proposal as one undoable step. */
    fun accept() {
        if (dropIfStale()) return
        val proposal = outcome as? AgentOutcome.Proposal ?: return
        val active = session ?: return
        when (val result = active.accept("Assistant: " + proposal.summary.take(LABEL_LENGTH))) {
            is AcceptResult.Accepted -> {
                outcome = null
                notice = null
                progress.clear()
            }

            is AcceptResult.Refused -> {
                notice = result.message
            }
        }
    }

    fun reject() {
        session?.reject()
        outcome = null
        notice = null
        progress.clear()
    }

    fun cancel() {
        job?.cancel()
    }

    private fun start(
        keepProgress: Boolean = false,
        work: suspend (AgentSession, (AgentEvent) -> Unit) -> AgentOutcome,
    ) {
        dropIfStale()
        val active = sessionNow() ?: return
        if (!keepProgress) progress.clear()
        outcome = null
        notice = null
        running = true
        job =
            scope.launch {
                try {
                    val result = work(active, ::record)
                    outcome = result.takeUnless { it is AgentOutcome.Failed }
                    notice = (result as? AgentOutcome.Failed)?.message
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
                }
            }
    }

    private fun record(event: AgentEvent) {
        if (event !is AgentEvent.UsedTool) return
        val line =
            when {
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
        return AgentSession(editor.engine, editor.editor, editor.sceneId, chosen).also { session = it }
    }

    /** Drops a proposal or question whose project or scene is gone; `true` when one was dropped. */
    private fun dropIfStale(): Boolean {
        val made = sessionFor ?: return false
        if (made.second == editor.projectGeneration && made.third == editor.sceneId) return false
        val dropped =
            when (outcome) {
                is AgentOutcome.Proposal -> DROPPED
                is AgentOutcome.Question -> DROPPED_QUESTION
                else -> null
            }
        reset(dropped)
        return dropped != null
    }

    private fun reset(message: String?) {
        job?.cancel()
        session?.reject()
        session = null
        sessionFor = null
        model = null
        outcome = null
        progress.clear()
        notice = message
    }

    /** Which model answers now: the creator's own key, SpawnKit AI for an account, or the demo. */
    private fun modelKey(): String =
        when {
            useLive && secrets.read(KEY_NAME) != null -> OWN_KEY
            else -> hosted()?.let { HOSTED + it.key } ?: DEMO
        }

    /** The model for the current choice, made once per choice; live models get the key and nothing else does. */
    private fun currentModel(): AgentModel {
        val key = modelKey()
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
        private const val CANCELLED = "Cancelled. Nothing in your game changed."
        private const val DROPPED = "Your game changed, so the proposal was dropped. Nothing was applied."
        private const val DROPPED_QUESTION = "Your game changed, so the question was dropped. Nothing was applied."

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
