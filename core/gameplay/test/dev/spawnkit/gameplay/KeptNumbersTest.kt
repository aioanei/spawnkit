package dev.spawnkit.gameplay

import dev.spawnkit.editing.ComponentDoc
import dev.spawnkit.editing.EditOperation
import dev.spawnkit.editing.Editor
import dev.spawnkit.editing.OperationResult
import dev.spawnkit.editing.buildSceneWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Game-wide values (C14): numbers marked "Keep across scenes" carry their value to the next scene by tag. */
class KeptNumbersTest {
    private val engine = StandardEngine()
    private val editor = Editor(engine.runtime, programs = engine.programs)
    private val first =
        editor.project.scenes
            .first()
            .id

    private fun scene(name: String): String =
        assertIs<OperationResult.Applied>(editor.apply(EditOperation.AddScene(name))).createdIds.single()

    private fun holder(
        scene: String,
        tag: String,
        value: Long,
        keep: Boolean,
    ): String =
        assertIs<OperationResult.Applied>(
            editor.apply(
                EditOperation.AddObject(
                    scene,
                    "$tag holder",
                    components =
                        listOf(
                            ComponentDoc("gameplay:tag", mapOf("tag" to tag)),
                            ComponentDoc("gameplay:number", mapOf("number" to value, "keep" to keep)),
                        ),
                ),
            ),
        ).createdIds.single()

    private fun value(
        built: dev.spawnkit.editing.SceneWorld,
        id: String,
    ): Long = built.world.get(built.entity(id)!!, engine.gameplay.number)!!.value

    @Test
    fun aKeptScoreSurvivesTwoLevels() {
        val two = scene("Level 2")
        val three = scene("Level 3")
        val start = holder(first, "coins", 7, keep = true)
        val middle = holder(two, "Coins ", 0, keep = true)
        val end = holder(three, "coins", 0, keep = true)
        val memory = mutableMapOf<String, Long>()
        val one = buildSceneWorld(engine.runtime, editor.project, first, engine.programs)
        val second = buildSceneWorld(engine.runtime, editor.project, two, engine.programs)
        val third = buildSceneWorld(engine.runtime, editor.project, three, engine.programs)

        engine.carryKeptNumbers(one.world, second.world, memory)
        assertEquals(7, value(second, middle), "tags match ignoring case and spaces")
        second.world.set(second.entity(middle)!!, engine.gameplay.number, NumberValue(9, keep = true))
        engine.carryKeptNumbers(second.world, third.world, memory)

        assertEquals(7, value(one, start))
        assertEquals(9, value(third, end))
    }

    @Test
    fun numbersNotKeptStartFresh() {
        val two = scene("Level 2")
        holder(first, "lives", 3, keep = false)
        val next = holder(two, "lives", 5, keep = true)
        val one = buildSceneWorld(engine.runtime, editor.project, first, engine.programs)
        val second = buildSceneWorld(engine.runtime, editor.project, two, engine.programs)

        engine.carryKeptNumbers(one.world, second.world, mutableMapOf())

        assertEquals(5, value(second, next))
    }

    @Test
    fun aValueMissingFromOneSceneComesBackLater() {
        val shop = scene("Shop")
        val level = scene("Level 2")
        holder(first, "coins", 4, keep = true)
        val back = holder(level, "coins", 0, keep = true)
        val memory = mutableMapOf<String, Long>()
        val one = buildSceneWorld(engine.runtime, editor.project, first, engine.programs)
        val middle = buildSceneWorld(engine.runtime, editor.project, shop, engine.programs)
        val last = buildSceneWorld(engine.runtime, editor.project, level, engine.programs)

        engine.carryKeptNumbers(one.world, middle.world, memory)
        engine.carryKeptNumbers(middle.world, last.world, memory)

        assertEquals(4, value(last, back))
    }

    @Test
    fun keepIsAnEditableNumberProperty() {
        val property =
            engine.gameplay.numberDescriptor.properties
                .single { it.id == "keep" }

        assertEquals("Keep across scenes", property.authoring.name)
        assertEquals(false, property.default)
    }
}
