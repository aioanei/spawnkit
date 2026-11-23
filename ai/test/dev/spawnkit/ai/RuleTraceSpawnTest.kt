package dev.spawnkit.ai

import dev.spawnkit.editing.ComponentDoc
import dev.spawnkit.editing.EditOperation
import dev.spawnkit.editing.Editor
import dev.spawnkit.editing.OperationResult
import dev.spawnkit.gameplay.Action
import dev.spawnkit.gameplay.Condition
import dev.spawnkit.gameplay.Rule
import dev.spawnkit.gameplay.RuleBlocks
import dev.spawnkit.gameplay.SpawnPlace
import dev.spawnkit.gameplay.StandardEngine
import dev.spawnkit.gameplay.Target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Agents' rule traces play like Play does (C14): templates stay out until spawned, and copies run their rules. */
class RuleTraceSpawnTest {
    private val engine = StandardEngine()
    private val editor = Editor(engine.runtime, programs = engine.programs)
    private val scene =
        editor.project.scenes
            .first()
            .id

    private fun add(
        name: String,
        extra: List<ComponentDoc>,
        rule: Rule,
    ): String {
        val components = listOf(ComponentDoc("graphics:transform"), ComponentDoc("gameplay:number")) + extra
        val id = assertIs<OperationResult.Applied>(editor.apply(EditOperation.AddObject(scene, name, components = components))).createdIds.single()
        editor.apply(EditOperation.SetRules(scene, id, RuleBlocks.compile(listOf(rule))))
        return id
    }

    @Test
    fun aTemplatesRulesRunOnlyInItsCopies() {
        add(
            "Enemy",
            listOf(ComponentDoc("gameplay:tag", mapOf("tag" to "enemy")), ComponentDoc("gameplay:template")),
            Rule(Condition.Always, actions = listOf(Action.ChangeNumber(Target.Self, 1))),
        )
        add("Spawner", emptyList(), Rule(Condition.EverySeconds(1), actions = listOf(Action.Spawn("enemy", SpawnPlace.Self))))

        val report = RuleTrace(engine).run(editor.project, scene, seconds = 1.5)

        val enemy = report.rules.single { it.objectName == "Enemy" }
        // The spawner fires at tick 60; the copy appears after that tick and runs from tick 61 to 90.
        assertEquals(30, enemy.rule.count)
        assertEquals(61, enemy.rule.firstTick)
    }
}
