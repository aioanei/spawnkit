package dev.spawnkit.ai

import dev.spawnkit.editing.EditOperation
import dev.spawnkit.editing.Editor
import dev.spawnkit.editing.StagedChanges
import dev.spawnkit.gameplay.StandardEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    @Test
    fun theAgentSeesTheWholeGameAndLooksInsideAnyScene() {
        editor.apply(EditOperation.AddScene("Level 1"))
        val level =
            editor.project.scenes
                .last()
                .id
        editor.apply(EditOperation.AddObject(level, "Hero"))
        val toolbox = AgentToolbox(engine, StagedChanges(editor), main)

        val overview = json(toolbox.call(AgentToolbox.LOOK, json("{}")).text)
        val inside = json(toolbox.call(AgentToolbox.LOOK, json("""{"scene":"$level"}""")).text)
        val missing = toolbox.call(AgentToolbox.LOOK, json("""{"scene":"scene-404"}"""))

        assertEquals(main, overview["scene"]!!.jsonPrimitive.content, "the session's scene by default")
        val scenes = overview["scenes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(main, level), scenes.map { it["id"]!!.jsonPrimitive.content })
        assertEquals("true", scenes.first()["start"]!!.jsonPrimitive.content, "the first scene is where play starts")
        assertEquals("1", scenes.last()["objects"]!!.jsonPrimitive.content)
        assertTrue("ads" in overview && "images" in overview)
        assertEquals(level, inside["scene"]!!.jsonPrimitive.content)
        assertEquals(listOf("Hero"), inside["objects"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertIs<ToolOutcome.Refused>(missing)
    }

    @Test
    fun checksAndTracesReachOtherScenesAndCheckingAllNamesTheScene() {
        editor.apply(EditOperation.AddScene("Level 1"))
        val level =
            editor.project.scenes
                .last()
                .id
        val staged = StagedChanges(editor)
        val toolbox = AgentToolbox(engine, staged, main)
        val coin =
            json(
                toolbox
                    .call(
                        "add_object",
                        json("""{"scene":"$level","name":"Coin","components":[{"component":"graphics:shape"}]}"""),
                    ).text,
            )["created"]!!
                .jsonArray
                .single()
                .jsonPrimitive.content
        val rule = """{"scene":"$level","object":"$coin","when":{"block":"Tapped"},"do":[{"block":"GoToScene","scene":"$main"}]}"""
        assertIs<ToolOutcome.Staged>(toolbox.call("add_rule", json(rule)))

        val here = toolbox.call(AgentToolbox.CHECK, json("""{"scene":"$level"}"""))
        val all = toolbox.call(AgentToolbox.CHECK, json("""{"scene":"all"}"""))
        val trace = toolbox.call(AgentToolbox.TRACE, json("""{"scene":"$level","seconds":1}"""))

        assertTrue("No problems" in here.text, here.text)
        assertTrue("Main scene" in all.text && "Level 1" in all.text, all.text)
        assertTrue("Coin" in assertIs<ToolOutcome.Looked>(trace).text, trace.text)
        assertIs<ToolOutcome.Refused>(toolbox.call(AgentToolbox.CHECK, json("""{"scene":"scene-404"}""")))
    }
}
