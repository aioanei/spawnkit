package dev.spawnkit.ai

import dev.spawnkit.editing.ComponentDoc
import dev.spawnkit.editing.ObjectDoc
import dev.spawnkit.editing.Project
import dev.spawnkit.editing.buildSceneWorld
import dev.spawnkit.gameplay.Action
import dev.spawnkit.gameplay.Rule
import dev.spawnkit.gameplay.RuleBlocks
import dev.spawnkit.gameplay.RuleItem
import dev.spawnkit.gameplay.StandardEngine
import dev.spawnkit.gameplay.Target
import dev.spawnkit.runtime.InputFrame

/** Input held during a headless test run: [action] is down from [fromSecond] (inclusive) to [toSecond] (exclusive). */
public data class HeldInput(
    /** Input action ID, for example `input:right`. */
    public val action: String,
    /** Start, in seconds of play. */
    public val fromSecond: Double,
    /** End, in seconds of play. */
    public val toSecond: Double,
)

/** How often one condition or rule was true during a run, and when first. */
public data class Firing(
    /** Plain description, for example "Touching player". */
    public val description: String,
    /** Moments (ticks) it was true. */
    public val count: Long,
    /** First moment it was true, counted from 1, or `null`. */
    public val firstTick: Long?,
)

/** The trace of one block rule: the whole rule and each of its conditions. */
public data class RuleFiring(
    /** Object ID. */
    public val objectId: String,
    /** Object name. */
    public val objectName: String,
    /** Rule number as the builder shows it, from 1. */
    public val ruleNumber: Int,
    /** The rule (all conditions together, then its actions). */
    public val rule: Firing,
    /** Each condition on its own: When first, then And if blocks. */
    public val conditions: List<Firing>,
)

/** Result of [RuleTrace.run]. */
public data class TraceReport(
    /** Moments played. */
    public val ticks: Long,
    /** Block rules of traced objects. */
    public val rules: List<RuleFiring>,
    /** Objects whose rules could not run, with the problem, by object name. */
    public val problems: Map<String, String>,
    /** Advanced rules that were run but cannot be traced block by block, by object name and rule number. */
    public val untraced: List<Pair<String, Int>>,
) {
    /** A plain-language explanation for creators and agents. */
    public fun explain(): String =
        buildString {
            appendLine("Played ${seconds(ticks)} headlessly in the real engine.")
            for ((name, problem) in problems) appendLine("$name's rules did not run: $problem")
            for (firing in rules) {
                val title = "Rule ${firing.ruleNumber} of ${firing.objectName} (${firing.rule.description})"
                if (firing.rule.count > 0) {
                    appendLine("$title ran ${times(firing.rule.count)}, first at ${seconds(firing.rule.firstTick!! - 1)}.")
                } else {
                    val never = firing.conditions.filter { it.count == 0L }
                    val reason =
                        if (never.isEmpty()) {
                            "each condition was true at some moment, but never all at the same moment"
                        } else {
                            never.joinToString(" and ") { "\"${it.description}\"" } + " " +
                                if (never.size == 1) "was never true" else "were never true"
                        }
                    appendLine("$title never ran: $reason.")
                }
            }
            for ((name, number) in untraced) {
                appendLine(
                    "Rule $number of $name is an advanced rule; it ran but is not traced block by block.",
                )
            }
        }.trimEnd()

    private fun seconds(ticks: Long): String {
        val tenths = ticks * 10 / TICKS_PER_SECOND
        return "${tenths / 10}.${tenths % 10} s"
    }

    private fun times(count: Long): String = if (count == 1L) "once" else "$count times"
}

/**
 * Explains rules from real play: builds a copy of the scene in which every block rule of the traced objects, and each
 * of its conditions, also counts into a hidden probe object, then plays it headlessly with scripted input. Probes use
 * only standard blocks, so traced rules run exactly as in Play; probe objects have no placement or look, so nothing
 * can touch, see or count them.
 */
public class RuleTrace(
    private val engine: StandardEngine,
) {
    /** Plays scene [sceneId] of [project] for [seconds] with [input], tracing [objectIds] (all objects with rules when empty). */
    public fun run(
        project: Project,
        sceneId: String,
        seconds: Double,
        input: List<HeldInput> = emptyList(),
        objectIds: List<String> = emptyList(),
    ): TraceReport {
        require(seconds > 0 && seconds <= MAX_SECONDS) { "Play from a moment up to $MAX_SECONDS seconds" }
        val scene = requireNotNull(project.scene(sceneId)) { "There is no scene '$sceneId'" }
        val traced = scene.objects.filter { it.rules.isNotEmpty() && (objectIds.isEmpty() || it.id in objectIds) }
        val probes = mutableListOf<Probe>()
        val untraced = mutableListOf<Pair<String, Int>>()
        var counter = 0
        val instrumented =
            scene.objects.map { obj ->
                if (obj !in traced) return@map obj
                val extra = mutableListOf<Rule>()
                RuleBlocks.decompile(obj.rules).forEachIndexed { index, item ->
                    when (item) {
                        is RuleItem.Advanced -> {
                            untraced += obj.name to index + 1
                        }

                        is RuleItem.Blocks -> {
                            val rule = item.rule
                            val whole =
                                Probe(
                                    obj,
                                    index,
                                    null,
                                    "${PROBE}${counter++}",
                                    "When " + RuleBlocks.describe(rule.condition).lowercase(),
                                )
                            probes += whole
                            extra += Rule(rule.condition, rule.alsoIf, listOf(whole.count()))
                            (listOf(rule.condition) + rule.alsoIf).forEachIndexed { k, condition ->
                                val probe = Probe(obj, index, k, "${PROBE}${counter++}", RuleBlocks.describe(condition))
                                probes += probe
                                extra += Rule(condition, actions = listOf(probe.count()))
                            }
                        }
                    }
                }
                // Recompiling keeps block rules identical and advanced rules unchanged; probes follow with their own numbers.
                obj.copy(rules = RuleBlocks.compileItems(RuleBlocks.decompile(obj.rules) + extra.map { RuleItem.Blocks(it) }))
            }
        val probeObjects =
            probes.map { probe ->
                ObjectDoc(
                    probe.tag,
                    probe.tag,
                    components = listOf(ComponentDoc("gameplay:tag", mapOf("tag" to probe.tag)), ComponentDoc("gameplay:number")),
                )
            }
        val copy =
            project.copy(
                scenes =
                    project.scenes.map {
                        if (it.id ==
                            sceneId
                        ) {
                            it.copy(objects = instrumented + probeObjects)
                        } else {
                            it
                        }
                    },
            )
        // Play as players do: templates stay out until a rule spawns a copy, which runs its (traced) rules too.
        val built = buildSceneWorld(engine.runtime, engine.playProject(copy), sceneId, engine.programs)
        val world = built.world
        val counts = LongArray(probes.size)
        val first = arrayOfNulls<Long>(probes.size)
        val ticks = (seconds * TICKS_PER_SECOND).toLong()
        for (tick in 1..ticks) {
            val time = (tick - 1).toDouble() / TICKS_PER_SECOND
            val down = input.filter { time >= it.fromSecond && time < it.toSecond }.mapNotNull { engine.actions[it.action] }.toSet()
            world.submitInput(InputFrame(down = down))
            world.step()
            engine.handleRequests(world, copy, sceneId)
            probes.forEachIndexed { i, probe ->
                val value = built.entity(probe.tag)?.let { world.get(it, engine.gameplay.number)?.value } ?: 0L
                if (value > counts[i]) {
                    if (first[i] == null) first[i] = tick
                    counts[i] = value
                }
            }
        }
        val problems =
            built.diagnostics
                .mapKeys { (id, _) ->
                    scene.find(id)?.name ?: id
                }.mapValues { it.value.first().message }
                .toMutableMap()
        for (entity in world.entities(engine.programs.behavior)) {
            val fault = world.get(entity, engine.programs.behavior)?.fault ?: continue
            val name = built.objectId(entity)?.let { scene.find(it)?.name } ?: continue
            problems[name] = "it stopped: ${fault.message}"
        }
        val rules =
            probes.filter { it.condition == null }.map { whole ->
                fun firing(probe: Probe) = probes.indexOf(probe).let { Firing(probe.description, counts[it], first[it]) }
                RuleFiring(
                    whole.obj.id,
                    whole.obj.name,
                    whole.rule + 1,
                    firing(whole),
                    probes.filter { it.obj == whole.obj && it.rule == whole.rule && it.condition != null }.map(::firing),
                )
            }
        return TraceReport(ticks, rules, problems, untraced)
    }

    private class Probe(
        val obj: ObjectDoc,
        val rule: Int,
        val condition: Int?,
        val tag: String,
        val description: String,
    ) {
        fun count(): Action = Action.ChangeNumber(Target.Tagged(tag), 1)
    }

    private companion object {
        const val PROBE = "spawnkit-probe-"
        const val MAX_SECONDS = 600.0
    }
}

private const val TICKS_PER_SECOND = 60L
