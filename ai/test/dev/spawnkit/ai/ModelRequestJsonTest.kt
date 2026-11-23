package dev.spawnkit.ai

import dev.spawnkit.editing.Editor
import dev.spawnkit.editing.StagedChanges
import dev.spawnkit.editing.ToolDefinition
import dev.spawnkit.gameplay.StandardEngine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The hosted model's wire format (C11a): requests and replies survive a round trip unchanged. */
class ModelRequestJsonTest {
    private val call = ToolCall("call-1", "set_property", JsonObject(mapOf("value" to JsonPrimitive(5), "name" to JsonPrimitive("Coin ✨"))))

    @Test
    fun requestsRoundTrip() {
        val request =
            ModelRequest(
                system = "Be helpful.",
                messages =
                    listOf(
                        AgentMessage.User("Make coins worth 5 \"points\""),
                        AgentMessage.Assistant(null, listOf(call)),
                        AgentMessage.ToolReplies(listOf(ToolReply("call-1", "set_property", "Staged."))),
                        AgentMessage.Assistant("Done.", emptyList()),
                    ),
                tools = listOf(ToolDefinition("set_property", "Sets a property.", JsonObject(mapOf("type" to JsonPrimitive("object"))))),
            )

        val decoded = ModelRequestJson.decodeRequest(ModelRequestJson.encodeRequest(request))

        assertEquals(request.system, decoded.system)
        assertEquals(request.messages, decoded.messages)
        assertEquals(
            request.tools.map {
                Triple(it.name, it.description, it.inputSchema)
            },
            decoded.tools.map { Triple(it.name, it.description, it.inputSchema) },
        )
    }

    @Test
    fun repliesRoundTrip() {
        val reply = ModelReply("Here you go.", listOf(call), Usage(1200, 340))

        assertEquals(reply, ModelRequestJson.decodeReply(ModelRequestJson.encodeReply(reply)))
        assertEquals(
            ModelReply(null, emptyList()),
            ModelRequestJson.decodeReply(ModelRequestJson.encodeReply(ModelReply(null, emptyList()))),
        )
    }

    @Test
    fun malformedJsonIsRefusedPlainly() {
        assertFailsWith<IllegalArgumentException> { ModelRequestJson.decodeRequest("{not json") }
        assertFailsWith<IllegalArgumentException> {
            ModelRequestJson.decodeRequest(
                """{"system":"x","messages":[{"role":"robot"}],"tools":[]}""",
            )
        }
        assertFailsWith<IllegalArgumentException> { ModelRequestJson.decodeReply("""{"calls":[{"id":1}]}""") }
    }

    @Test
    fun toolDefinitionsDependOnlyOnTheEngine() {
        val engine = StandardEngine()
        val toolbox = AgentToolbox(engine, StagedChanges(Editor(engine.runtime)), "scene-1")

        val fixed = AgentToolbox.definitions(engine)

        assertEquals(toolbox.tools.map { it.name to it.description }, fixed.map { it.name to it.description })
        assertEquals(toolbox.tools.map { it.inputSchema }, fixed.map { it.inputSchema })
    }
}
