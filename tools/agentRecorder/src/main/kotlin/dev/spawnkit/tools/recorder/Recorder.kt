package dev.spawnkit.tools.recorder

import dev.spawnkit.ai.AgentModel
import dev.spawnkit.ai.Cost
import dev.spawnkit.ai.ModelInfo
import dev.spawnkit.ai.ModelReply
import dev.spawnkit.ai.ModelRequest
import dev.spawnkit.ai.RecordedSession
import dev.spawnkit.ai.SessionRecorder
import dev.spawnkit.editing.Editor
import dev.spawnkit.editing.Project
import dev.spawnkit.editing.ProjectCodec
import dev.spawnkit.gameplay.StandardEngine
import dev.spawnkit.presets.GamePresets
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/** A session to record: the creator's [prompts] on a preset game, or on a blank game when [preset] is `null`. */
class Scenario(
    /** File name of the recording, without extension. */
    val name: String,
    /** Preset ID from `presets/`, or `null` for a blank game. */
    val preset: String?,
    /** What the creator types, in order. */
    val prompts: List<String>,
    /** What the recording shows; kept in the file. */
    val note: String,
)

/** The demo set: building a game from nothing, adding to a preset, and follow-up edits to existing rules. */
val DEMO_SCENARIOS: List<Scenario> =
    listOf(
        Scenario(
            "blank-collect-game",
            null,
            listOf(
                "Make a small game: a blue player I move with the arrow keys, five yellow coins to collect, and a score that counts them.",
            ),
            "From a blank game, the assistant builds a whole collect game from one prompt.",
        ),
        Scenario(
            "coin-dash-enemy",
            "coin-dash",
            listOf(
                "Add a red enemy that moves left and right across the middle of the level. If the player touches it, restart the level.",
            ),
            "Coin Dash: the assistant adds a bouncing enemy that restarts the level.",
        ),
        Scenario(
            "tap-targets-tuning",
            "tap-targets",
            listOf("Make the fast targets worth 3 points instead of 1.", "Give players 10 more seconds."),
            "Tap Targets: two follow-up edits to rules that already exist.",
        ),
    )

/** Model calls allowed per prompt; lower than the builder's default to bound the cost of a recording. */
const val MAX_STEPS: Int = 20

/**
 * Records each scenario with [model] into [out]: `<name>.json` (the replayable session) and `<name>.spawnkit` (the game
 * after accepting every proposal, to open in the builder). Reports progress through [report]. Returns `false` when a
 * scenario failed, left a question open or could not be accepted.
 */
fun recordAll(
    model: AgentModel,
    scenarios: List<Scenario>,
    out: Path,
    recordedOn: String,
    report: (String) -> Unit,
    maxSteps: Int = MAX_STEPS,
): Boolean {
    Files.createDirectories(out)
    var ok = true
    for (scenario in scenarios) {
        val engine = StandardEngine()
        val preset = scenario.preset?.let { id -> GamePresets.all.single { it.id == id } }
        val project = preset?.project() ?: Project.blank()
        val editor = Editor(engine.runtime, project, programs = engine.programs)
        // Presets open on their title screen; the game itself is the second scene.
        val scene = if (preset != null) project.scenes[1].id else project.scenes.first().id
        report("== ${scenario.name} (${preset?.name ?: "blank game"}, ${model.info.label}) ==")
        val run =
            runBlocking { SessionRecorder(engine, editor, scene, maxSteps).record(model, scenario.prompts, recordedOn, scenario.note) }
        run.log.forEach { report("  $it") }
        report("  Cost: ${describe(run.cost)}")
        out.resolve("${scenario.name}.json").writeText(RecordedSession.encode(run.recording))
        out.resolve("${scenario.name}.spawnkit").writeText(ProjectCodec.encode(editor.saveSnapshot()))
        report("  Wrote ${scenario.name}.json and ${scenario.name}.spawnkit")
        if (run.log.any { it.startsWith("Failed") || it.startsWith("Question left open") || it.startsWith("Proposal not accepted") }) {
            ok =
                false
        }
    }
    return ok
}

private fun describe(cost: Cost): String =
    "${cost.usage.inputTokens} input + ${cost.usage.outputTokens} output tokens" +
        (cost.dollars?.let { ", about ${dollars(it)}" } ?: ", no charge")

/**
 * [line] with every occurrence of [key] replaced. GitHub hides secrets in logs but not inside uploaded artifacts, so
 * every reported line goes through this before it is printed into the summary.
 */
fun hideKey(
    line: String,
    key: String,
): String = if (key.isEmpty()) line else line.replace(key, "[key hidden]")

/** "$1.25". */
internal fun dollars(amount: Double): String = "$" + "%.2f".format(amount)

/**
 * Stops calling [delegate] once the estimated spend reaches [maxDollars], so one workflow run has a fixed upper cost.
 * The session then fails with the budget message and the recorder stops.
 */
class CappedModel(
    private val delegate: AgentModel,
    private val maxDollars: Double,
) : AgentModel {
    /** Estimated dollars spent so far. */
    var spent: Double = 0.0
        private set

    override val info: ModelInfo get() = delegate.info

    override suspend fun reply(request: ModelRequest): ModelReply {
        check(spent < maxDollars) { "The recording budget of ${dollars(maxDollars)} is spent" }
        return delegate.reply(request).also { spent += Cost.of(it.usage, info).dollars ?: 0.0 }
    }
}
