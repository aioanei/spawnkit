package dev.spawnkit.ai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import dev.spawnkit.editing.Editor
import dev.spawnkit.editing.StagedChanges
import dev.spawnkit.gameplay.StandardEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The Koog adapter, with a fake executor: no network, no key and no live model call in tests. */
class KoogModelTest {
    private class FakeExecutor(
        val answer: Message.Assistant,
    ) : PromptExecutor() {
        var prompt: Prompt? = null
        var tools: List<ToolDescriptor> = emptyList()

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): Message.Assistant {
            this.prompt = prompt
            this.tools = tools
            return answer
        }

        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): Flow<StreamFrame> = emptyFlow()

        override suspend fun moderate(
            prompt: Prompt,
            model: LLModel,
        ): ModerationResult = throw UnsupportedOperationException()

        override fun close() {}
    }

    @Test
    fun everyDerivedToolBecomesAKoogDescriptorAndRepliesComeBackAsToolCalls() =
        runTest {
            val engine = StandardEngine()
            val editor = Editor(engine.runtime, programs = engine.programs)
            val tools =
                AgentToolbox(
                    engine,
                    StagedChanges(editor),
                    editor.project.scenes
                        .first()
                        .id,
                ).tools
            val call = MessagePart.Tool.Call("toolu_1", "add_rule", JsonObject(mapOf("rule" to JsonPrimitive(1))))
            val executor =
                FakeExecutor(
                    Message.Assistant(
                        listOf(MessagePart.Text("Adding the rule."), call),
                        ResponseMetaInfo(Instant.fromEpochSeconds(0), inputTokensCount = 1200, outputTokensCount = 80),
                    ),
                )
            val info = ModelInfo("test-model", "Test", live = true, label = "Live: test-model", 3.0, 15.0)
            val model = KoogModel(executor, LLModel(LLMProvider.Anthropic, "test-model"), info)
            val request =
                ModelRequest(
                    AgentSession.SYSTEM,
                    listOf(
                        AgentMessage.User("Make coins worth 5"),
                        AgentMessage.Assistant(null, listOf(ToolCall("toolu_0", AgentToolbox.LOOK, JsonObject(emptyMap())))),
                        AgentMessage.ToolReplies(listOf(ToolReply("toolu_0", AgentToolbox.LOOK, "{}"))),
                    ),
                    tools,
                )
            val reply = model.reply(request)
            assertEquals("Adding the rule.", reply.text)
            assertEquals(listOf(ToolCall("toolu_1", "add_rule", call.argsJson)), reply.calls)
            assertEquals(Usage(1200, 80), reply.usage)

            val messages = executor.prompt!!.messages
            assertIs<Message.System>(messages[0])
            val result = (messages[3] as Message.User).parts.single() as MessagePart.Tool.Result
            assertEquals("toolu_0", result.id)
            assertEquals(tools.map { it.name }, executor.tools.map { it.name })
            val addRule = executor.tools.first { it.name == "add_rule" }
            assertEquals(listOf("scene", "object", "when"), addRule.requiredParameters.map { it.name })
            // Koog's Anthropic client refuses AnyOf, so block choices become one object chosen by its block ID.
            val whenType = assertIs<ToolParameterType.Object>(addRule.requiredParameters.last().type)
            val block = whenType.properties.first { it.name == "block" }
            assertEquals(listOf("block"), whenType.requiredProperties)
            assertTrue("Touching" in assertIs<ToolParameterType.Enum>(block.type).entries, "Blocks are chosen by their block ID")
            assertTrue(whenType.properties.any { it.name == "tag" }, "Each block's settings are offered")
            executor.tools.forEach { assertFalse(hasAnyOf(it), "${it.name} has no AnyOf parameter") }
            assertFalse("key" in info.toString().lowercase(), "Model info carries no credentials")
        }

    private fun hasAnyOf(tool: ToolDescriptor): Boolean = (tool.requiredParameters + tool.optionalParameters).any { hasAnyOf(it.type) }

    private fun hasAnyOf(type: ToolParameterType): Boolean =
        when (type) {
            is ToolParameterType.AnyOf -> true
            is ToolParameterType.List -> hasAnyOf(type.itemsType)
            is ToolParameterType.Object -> type.properties.any { hasAnyOf(it.type) }
            else -> false
        }

    @Test
    fun choicesTheModelMadeInTheFlatFormComeBackInTheToolsOwnForm() =
        runTest {
            val engine = StandardEngine()
            val tools = AgentToolbox.definitions(engine)
            val rule =
                """{"scene":"scene-1","object":"obj-2","when":{"block":"Touching","tag":"player"},""" +
                    """"do":[{"block":"ChangeNumber","target":{"kind":"tagged","tag":"score"},"amount":1}]}"""
            val executor =
                executorFor(
                    set("toolu_1", "graphics:shape", "width", """{"number":2.5}"""),
                    set("toolu_2", "graphics:text", "text", """{"text":"Hi"}"""),
                    set("toolu_3", "graphics:sprite", "pixelated", """{"yes_no":true}"""),
                    MessagePart.Tool.Call("toolu_4", "add_rule", json(rule)),
                )
            val info = ModelInfo("test-model", "Test", live = true, label = "Live: test-model")
            val model = KoogModel(executor, LLModel(LLMProvider.Anthropic, "test-model"), info)
            val calls = model.reply(ModelRequest("Hi", emptyList(), tools)).calls

            assertEquals(JsonPrimitive(2.5), calls[0].input["value"])
            assertEquals(JsonPrimitive("Hi"), calls[1].input["value"])
            assertEquals(JsonPrimitive(true), calls[2].input["value"])
            assertEquals(json(rule), calls[3].input, "flattened block choices already have the tool's own form")
        }

    private fun set(
        id: String,
        component: String,
        property: String,
        value: String,
    ) = MessagePart.Tool.Call(
        id,
        "set_property",
        json("""{"scene":"scene-1","object":"obj-2","component":"$component","property":"$property","value":$value}"""),
    )

    private fun json(text: String) =
        kotlinx.serialization.json.Json
            .parseToJsonElement(text) as JsonObject

    private fun executorFor(vararg calls: MessagePart.Tool.Call) =
        FakeExecutor(Message.Assistant(calls.toList(), ResponseMetaInfo(Instant.fromEpochSeconds(0))))

    @Test
    fun outputTokensAreCappedWhenAsked() =
        runTest {
            val answer = Message.Assistant(listOf(MessagePart.Text("Hi.")), ResponseMetaInfo(Instant.fromEpochSeconds(0)))
            val info = ModelInfo("test-model", "Test", live = true, label = "Live: test-model")
            val capped = FakeExecutor(answer)
            val uncapped = FakeExecutor(answer)

            KoogModel(
                capped,
                LLModel(LLMProvider.Anthropic, "test-model"),
                info,
                maxOutputTokens = 512,
            ).reply(ModelRequest("Be brief.", emptyList(), emptyList()))
            KoogModel(
                uncapped,
                LLModel(LLMProvider.Anthropic, "test-model"),
                info,
            ).reply(ModelRequest("Be brief.", emptyList(), emptyList()))

            assertEquals(512, capped.prompt!!.params.maxTokens)
            assertEquals(null, uncapped.prompt!!.params.maxTokens)
        }

    @Test
    fun theAnthropicFactoryNeedsAKeyAndLabelsTheModelLiveWithoutStoringTheKey() {
        val error = runCatching { KoogModel.anthropic("  ") }.exceptionOrNull()
        assertEquals("Add an Anthropic API key in the assistant settings first", error?.message)
        val model = KoogModel.anthropic("sk-test-not-a-real-key")
        assertTrue(model.info.live)
        assertEquals("Live: claude-sonnet-5-5 (Anthropic)", model.info.label)
        assertFalse("sk-test" in model.info.toString())
    }
}
