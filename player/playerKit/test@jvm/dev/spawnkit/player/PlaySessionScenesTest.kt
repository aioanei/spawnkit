package dev.spawnkit.player

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull

/** Players switch scenes when a "Go to scene" block asks, at the end of the tick, with a fresh world (C9). */
class PlaySessionScenesTest {
    private val engine = StandardEngine()
    private val editor = Editor(engine.runtime, programs = engine.programs)
    private val title =
        editor.project.scenes
            .first()
            .id
    private val level = (editor.apply(EditOperation.AddScene("Level 1")) as OperationResult.Applied).createdIds.single()
    private val hero = add(level, "Hero")
    private val button = add(title, "Play button")

    private fun add(
        scene: String,
        name: String,
    ): String =
        (
            editor.apply(
                EditOperation.AddObject(
                    scene,
                    name,
                    components =
                        listOf(
                            ComponentDoc("graphics:transform", mapOf("x" to 0.0, "y" to 0.0)),
                            ComponentDoc("graphics:shape", mapOf("width" to 2.0, "height" to 1.0)),
                        ),
                ),
            ) as OperationResult.Applied
        ).createdIds.single()

    private fun goTo(target: String) {
        val rule = Rule(Condition.Tapped, actions = listOf(Action.GoToScene(target)))
        editor.apply(EditOperation.SetRules(title, button, RuleBlocks.compile(listOf(rule))))
    }

    private fun PlaySession.tapAndTick() {
        pointerDown(1, 0.0, 0.0)
        pointerUp(1)
        scene.world.submitInput(nextInput())
        scene.world.step()
        afterTick()
    }

    @Test
    fun tappingPlayOnTheTitleReachesLevelOne() {
        goTo(level)
        val session = engine.playSession(editor.project, title)
        assertNull(session.scene.entity(hero), "The game starts on the title")

        session.tapAndTick()

        assertEquals(level, session.sceneId)
        assertNotNull(session.scene.entity(hero))
    }

    @Test
    fun keptNumbersCarryAcrossTheSceneChange() {
        goTo(level)
        val kept = { scene: String, value: Long ->
            (
                editor.apply(
                    EditOperation.AddObject(
                        scene,
                        "Coins",
                        components =
                            listOf(
                                ComponentDoc("gameplay:tag", mapOf("tag" to "coins")),
                                ComponentDoc("gameplay:number", mapOf("number" to value, "keep" to true)),
                            ),
                    ),
                ) as OperationResult.Applied
            ).createdIds.single()
        }
        kept(title, 12)
        val onLevel = kept(level, 0)
        val session = engine.playSession(editor.project, title)

        session.tapAndTick()

        assertEquals(
            12,
            session.scene.world
                .get(session.scene.entity(onLevel)!!, engine.gameplay.number)
                ?.value,
        )
    }

    @Test
    fun templatesAppearOnlyWhenARuleSpawnsThem() {
        val enemy =
            (
                editor.apply(
                    EditOperation.AddObject(
                        title,
                        "Enemy",
                        components =
                            listOf(
                                ComponentDoc("graphics:transform"),
                                ComponentDoc("gameplay:tag", mapOf("tag" to "enemy")),
                                ComponentDoc("gameplay:template"),
                            ),
                    ),
                ) as OperationResult.Applied
            ).createdIds.single()
        val spawn = Rule(Condition.Tapped, actions = listOf(Action.Spawn("enemy", SpawnPlace.Position(5.0, 1.0))))
        editor.apply(EditOperation.SetRules(title, button, RuleBlocks.compile(listOf(spawn))))
        val session = engine.playSession(editor.project, title)
        assertNull(session.scene.entity(enemy), "templates are left out of play")

        session.tapAndTick()

        val copy = engine.gameplay.tagged(session.scene.world, "enemy").single()
        assertEquals(
            5.0,
            session.scene.world
                .get(copy, engine.graphics.transform)
                ?.x,
        )
    }

    @Test
    fun goingToTheSameSceneRestartsIt() {
        goTo(title)
        val session = engine.playSession(editor.project, title)
        val first = session.scene

        session.tapAndTick()

        assertEquals(title, session.sceneId)
        assertNotSame(first, session.scene, "A fresh world")
    }

    @Test
    fun aMissingSceneKeepsPlayingWithAMessage() {
        goTo(level)
        editor.apply(EditOperation.RemoveScene(level))
        val session = engine.playSession(editor.project, title)
        val first = session.scene

        session.tapAndTick()

        assertEquals(title, session.sceneId)
        assertEquals(first, session.scene)
        assertEquals("A rule asked for a scene that no longer exists.", session.message)
    }
}
