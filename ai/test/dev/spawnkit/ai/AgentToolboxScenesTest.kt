package dev.spawnkit.ai

import dev.spawnkit.editing.EditOperation
import dev.spawnkit.editing.Editor
import dev.spawnkit.editing.StagedChanges
import dev.spawnkit.gameplay.StandardEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Scene tools derived for agents (C9) keep the scene the session works on, so inspection and traces stay valid. */
class AgentToolboxScenesTest {
    private val engine = StandardEngine()
    private val editor = Editor(engine.runtime, programs = engine.programs)
    private val main =
        editor.project.scenes
            .first()
            .id

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun theSessionsSceneCannotBeDeletedButOtherScenesCan() {
        editor.apply(EditOperation.AddScene("Bonus"))
        val bonus =
            editor.project.scenes
                .last()
                .id
        val staged = StagedChanges(editor)
        val toolbox = AgentToolbox(engine, staged, main)

        val refused = toolbox.call("remove_scene", json("""{"scene":"$main"}"""))
        val removed = toolbox.call("remove_scene", json("""{"scene":"$bonus"}"""))
        val look = toolbox.call(AgentToolbox.LOOK, json("{}"))

        assertTrue("working on this scene" in assertIs<ToolOutcome.Refused>(refused).text, refused.text)
        assertIs<ToolOutcome.Staged>(removed)
        assertIs<ToolOutcome.Looked>(look)
        assertEquals(listOf(main), staged.project.scenes.map { it.id })
    }
}
