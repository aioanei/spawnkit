package dev.spawnkit.ai

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.sun.net.httpserver.HttpServer
import dev.spawnkit.gameplay.StandardEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The real Koog Anthropic client with every derived tool, against a local stand-in for the Anthropic API: no network,
 * no key and no model (AGENTS.md). Koog 1.3.0 refuses `AnyOf` parameters before sending anything, which made every
 * live request with the full toolbox fail; this checks the request is built and sent, and the reply is understood.
 */
class KoogAnthropicWireTest {
    @Test
    fun everyToolReachesTheAnthropicApiAndChoicesComeBackInTheToolsOwnForm() =
        runBlocking {
            var sent: JsonObject? = null
            val reply =
                """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5","stop_reason":"tool_use",
                 "stop_sequence":null,"usage":{"input_tokens":1500,"output_tokens":40},
                 "content":[{"type":"tool_use","id":"toolu_1","name":"set_property",
                   "input":{"scene":"scene-1","object":"obj-2","component":"graphics:shape","property":"width","value":{"number":2.5}}}]}
                """.trimIndent()
            val server =
                HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                    createContext("/") { exchange ->
                        sent = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
                        val body = reply.encodeToByteArray()
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    }
                    start()
                }
            try {
                val base = AnthropicModels.Sonnet_5
                val llm = LLModel(LLMProvider.Anthropic, "claude-sonnet-5-5", base.capabilities, base.contextLength, base.maxOutputTokens)
                val settings =
                    AnthropicClientSettings(
                        modelVersionsMap = mapOf(llm to "claude-sonnet-5-5"),
                        baseUrl = "http://127.0.0.1:${server.address.port}",
                    )
                val client = AnthropicLLMClient("sk-test-not-a-real-key", settings, KtorKoogHttpClient.Factory())
                val model = KoogModel(MultiLLMPromptExecutor(client), llm, ModelInfo("claude-sonnet-5-5", "Anthropic", true, "Live"))
                val tools = AgentToolbox.definitions(StandardEngine())

                val answer = model.reply(ModelRequest(AgentSession.SYSTEM, listOf(AgentMessage.User("Make the box wider")), tools))

                val request = checkNotNull(sent) { "Nothing reached the API" }
                val sentTools =
                    (request["tools"] as JsonArray).map {
                        (it as JsonObject)["name"]!!.let { name ->
                            (name as JsonPrimitive).content
                        }
                    }
                assertEquals(tools.map { it.name }, sentTools)
                assertFalse("anyOf" in request["tools"].toString() || "oneOf" in request["tools"].toString())
                assertEquals(JsonPrimitive(2.5), answer.calls.single().input["value"], "the flat choice comes back as a plain value")
                assertEquals(Usage(1500, 40), answer.usage)
                assertTrue("sk-test" !in request.toString(), "the key is never in the body")
            } finally {
                server.stop(0)
            }
        }
}
