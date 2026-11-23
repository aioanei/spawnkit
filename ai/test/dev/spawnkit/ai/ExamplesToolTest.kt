package dev.spawnkit.ai

import dev.spawnkit.editing.Editor
import dev.spawnkit.editing.StagedChanges
import dev.spawnkit.gameplay.StandardEngine
import dev.spawnkit.presets.GamePresets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The preset games as the Game Architect's worked examples (C13a): read-only, in the form `look_at_game` uses. */
class ExamplesToolTest {
    private val engine = StandardEngine()
    private val editor = Editor(engine.runtime, programs = engine.programs)
    private val toolbox =
        AgentToolbox(
            engine,
            StagedChanges(editor),
            editor.project.scenes
                .first()
                .id,
        )

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun call(input: String): JsonObject = json(assertIs<ToolOutcome.Looked>(toolbox.call(AgentToolbox.EXAMPLE, json(input))).text)

    @Test
    fun theOverviewListsEveryExampleAndAnExampleListsItsScenes() {
        val all = call("{}")
        val ids = all["examples"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(GamePresets.all.map { it.id }, ids)
        assertTrue(all["examples"]!!.jsonArray.any { it.jsonObject["complete"]!!.jsonPrimitive.content == "true" })

        val tycoon = GamePresets.all.first { it.complete }
        val overview = call("""{"example":"${tycoon.id}"}""")
        assertEquals(tycoon.name, overview["game"]!!.jsonPrimitive.content)
        assertEquals(
            tycoon.project().scenes.map { it.name },
            overview["scenes"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun aSceneOfAnExampleReadsLikeLookingAtThatGame() {
        val example = GamePresets.all.first { it.complete }
        val project = example.project()
        val scene = project.scenes[1]

        val seen = call("""{"example":"${example.id}","scene":"${scene.id}"}""")

        val loaded = Editor(engine.runtime, project, programs = engine.programs)
        val look = json(AgentToolbox(engine, StagedChanges(loaded), scene.id).call(AgentToolbox.LOOK, json("{}")).text)
        assertEquals(look["objects"], seen["objects"])
        assertEquals(scene.name, seen["sceneName"]!!.jsonPrimitive.content)
    }

    @Test
    fun unknownExamplesAndScenesAreRefusedAndNothingChanges() {
        val before = editor.project
        assertIs<ToolOutcome.Refused>(toolbox.call(AgentToolbox.EXAMPLE, json("""{"example":"pong"}""")))
        val example = GamePresets.all.first().id
        assertIs<ToolOutcome.Refused>(toolbox.call(AgentToolbox.EXAMPLE, json("""{"example":"$example","scene":"scene-404"}""")))
        assertEquals(before, editor.project)
    }
}
