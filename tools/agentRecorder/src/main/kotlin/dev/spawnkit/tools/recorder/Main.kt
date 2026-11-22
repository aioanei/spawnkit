package dev.spawnkit.tools.recorder

import dev.spawnkit.ai.KoogModel
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.system.exitProcess

private const val DEFAULT_BUDGET = 3.0
private const val MAX_BUDGET = 20.0

/**
 * Records live assistant sessions with the Anthropic key in `ANTHROPIC_API_KEY` (a repository secret in the "Record AI
 * session" workflow). Writes into the directory given as the first argument (default `build/recordings`).
 *
 * - `SPAWNKIT_PROMPT`: record this one prompt instead of the demo set, on `SPAWNKIT_PRESET` (a preset ID, or blank).
 * - `SPAWNKIT_BUDGET`: the most this run may spend, in US dollars (default 3, at most 20).
 *
 * The key is only handed to the model client; it is never printed or written.
 */
fun main(args: Array<String>) {
    val key = System.getenv("ANTHROPIC_API_KEY")?.trim().orEmpty()
    if (key.isEmpty()) {
        System.err.println("Set ANTHROPIC_API_KEY to record live sessions.")
        exitProcess(2)
    }
    val budget =
        System
            .getenv("SPAWNKIT_BUDGET")
            ?.trim()
            ?.ifEmpty { null }
            ?.toDoubleOrNull() ?: DEFAULT_BUDGET
    if (budget <= 0.0 || budget > MAX_BUDGET) {
        System.err.println("SPAWNKIT_BUDGET must be more than 0 and at most ${dollars(MAX_BUDGET)}.")
        exitProcess(2)
    }
    val prompt = System.getenv("SPAWNKIT_PROMPT")?.trim().orEmpty()
    val scenarios =
        if (prompt.isEmpty()) {
            DEMO_SCENARIOS
        } else {
            val preset = System.getenv("SPAWNKIT_PRESET")?.trim()?.takeUnless { it.isEmpty() || it == "blank" }
            listOf(Scenario("custom", preset, listOf(prompt), "A prompt typed into the workflow form."))
        }
    val out = Path.of(args.getOrNull(0) ?: "build/recordings")
    val model = CappedModel(KoogModel.anthropic(key), budget)
    val ok = recordAll(model, scenarios, out, LocalDate.now(ZoneOffset.UTC).toString(), { println(hideKey(it, key)) })
    println("Spent about ${dollars(model.spent)} of the ${dollars(budget)} budget.")
    exitProcess(if (ok) 0 else 1)
}
