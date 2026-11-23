package dev.spawnkit.ai

import dev.spawnkit.editing.AcceptResult
import dev.spawnkit.editing.Editor
import dev.spawnkit.editing.OperationResult
import dev.spawnkit.editing.ProjectImages
import dev.spawnkit.gameplay.RuleBlocks
import dev.spawnkit.gameplay.RuleItem
import dev.spawnkit.gameplay.StandardEngine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `make_character` agent tool (C16b, ADR-0013, ADR-0009), with scripted fixtures standing in for a model's choices and
 * a fake maker standing in for the server: nothing leaves the device before the creator confirms with consent, and the
 * result is the same Character the builder makes.
 */
class CharacterToolTest {
    private val engine = StandardEngine()
    private val editor = Editor(engine.runtime, programs = engine.programs)
    private val scene =
        editor.project.scenes
            .first()
            .id

    private val pixel = ForgedImage("pixel", "Pixel art", ProjectImages.encode(TestPng.make(8, 8)), "Fake image-1, 2026-11-23")
    private val cartoon =
        ForgedImage("cartoon", "Cartoon", ProjectImages.encode(TestPng.make(8, 8) { _, _ -> 0xFF1E88E5 }), "Fake image-1, 2026-11-23")

    /** The server's forge, faked: it records each request and returns [result] or throws [failure]. */
    private class FakeMaker(
        var photo: Boolean = true,
        var result: CharacterResult = CharacterResult(emptyList(), emptyMap()),
        var failure: Exception? = null,
    ) : CharacterMaker {
        val sent = mutableListOf<Pair<List<String>, Boolean>>()

        override val photoAttached: Boolean get() = photo

        override fun unavailable(): String? = if (photo) null else "Attach a photo in the chat first."

        override suspend fun make(
            styles: List<String>,
            consent: Boolean,
        ): CharacterResult {
            sent += styles to consent
            failure?.let { throw it }
            return result
        }
    }

    /** Scripted fixture (not a recording of a model): one reply per model call. */
    private class Script(
        vararg replies: List<ToolCall>,
    ) : AgentModel {
        override val info = ModelInfo("Scripted fixture", "none", live = false, label = "Scripted fixture (no model is called)")
        private val queue = ArrayDeque(replies.toList())
        val requests = mutableListOf<ModelRequest>()

        override suspend fun reply(request: ModelRequest): ModelReply {
            requests += request
            return ModelReply(null, queue.removeFirst())
        }
    }

    private fun call(
        name: String,
        json: String = "{}",
    ) = ToolCall("call-$name", name, Json.parseToJsonElement(json).jsonObject)

    private val finish = listOf(call(AgentToolbox.FINISH, """{"summary":"Your character is ready."}"""))

    private fun make(json: String = """{"styles":["pixel","cartoon"],"use":"pixel"}""") = listOf(call(AgentToolbox.MAKE_CHARACTER, json))

    private fun session(
        model: AgentModel,
        maker: CharacterMaker?,
    ) = AgentSession(engine, editor, scene, model, characters = maker)

    /** The text of the reply to the last `make_character` call the model saw. */
    private fun Script.toolReply(): JsonObject =
        Json
            .parseToJsonElement(
                requests
                    .last()
                    .messages
                    .filterIsInstance<AgentMessage.ToolReplies>()
                    .flatMap { it.replies }
                    .last { it.name == AgentToolbox.MAKE_CHARACTER }
                    .text,
            ).jsonObject

    @Test
    fun nothingIsSentUntilTheCreatorConfirmsAndAcceptingIsOneUndoStep() =
        runTest {
            val maker = FakeMaker(result = CharacterResult(listOf(pixel, cartoon), emptyMap()))
            val model = Script(make(), finish)
            val session = session(model, maker)
            val before = editor.project

            val asked = assertIs<AgentOutcome.Confirmation>(session.ask("Make this person a character in the game."))
            assertEquals(listOf("pixel", "cartoon"), asked.styles)
            assertTrue(asked.message.contains("Pixel art") && asked.message.contains("Cartoon"), asked.message)
            assertEquals(emptyList(), maker.sent, "nothing leaves the device before the creator confirms")
            assertEquals(before, editor.project)
            val prompt =
                model.requests
                    .first()
                    .messages
                    .first() as AgentMessage.User
            assertTrue(prompt.text.contains(AgentSession.PHOTO_ATTACHED), "the model is told a photo is attached, never shown it")

            val proposal = assertIs<AgentOutcome.Proposal>(session.confirm(consent = true))
            assertEquals(listOf(listOf("pixel", "cartoon") to true), maker.sent)
            assertEquals(listOf(CharacterRecipe.LABEL), proposal.steps)
            assertEquals(before, editor.project, "still nothing changes until the creator accepts")
            val reply = model.toolReply()
            assertEquals("true", reply["ok"]?.jsonPrimitive?.content)
            val characterId = assertNotNull(reply["character"]).jsonPrimitive.content

            assertIs<AcceptResult.Accepted>(session.accept("Assistant: character"))
            assertEquals(listOf("Pixel art character", "Cartoon character"), editor.project.images.map { it.name })
            assertTrue(editor.project.images.all { it.provenance == "Fake image-1, 2026-11-23" })
            val character = assertNotNull(editor.project.scene(scene)?.find(characterId))
            val sprite = assertNotNull(character.component("graphics:sprite")).values
            assertEquals(
                editor.project.images
                    .first()
                    .id,
                sprite["asset"],
            )
            assertEquals(true, sprite["pixelated"])
            assertNotNull(character.component("gameplay:body"))
            assertEquals("player", character.component("gameplay:tag")?.values?.get("tag"))
            assertEquals(3, RuleBlocks.decompile(character.rules).filterIsInstance<RuleItem.Blocks>().size)
            editor.undo()
            assertEquals(before, editor.project, "one undo removes the character and its images")
        }

    @Test
    fun theAgentMakesWhatTheBuildersRecipeMakes() =
        runTest {
            val maker = FakeMaker(result = CharacterResult(listOf(pixel, cartoon), emptyMap()))
            val session = session(Script(make("""{"styles":["pixel","cartoon"],"use":"cartoon","x":2,"y":-1}"""), finish), maker)
            assertIs<AgentOutcome.Confirmation>(session.ask("Make me a cartoon character"))
            assertIs<AgentOutcome.Proposal>(session.confirm(consent = true))
            assertIs<AcceptResult.Accepted>(session.accept("Assistant: character"))
            val byAgent = editor.project

            val other = Editor(engine.runtime, programs = engine.programs)
            val planned =
                assertNotNull(CharacterRecipe.batch(listOf(pixel, cartoon), "cartoon", scene, 2.0, -1.0, other.saveSnapshot().nextId))
            assertIs<OperationResult.Applied>(other.apply(planned.operation))
            assertEquals(other.project, byAgent)
        }

    @Test
    fun consentIsRequiredAndDecliningSendsNothing() =
        runTest {
            val maker = FakeMaker(result = CharacterResult(listOf(pixel), emptyMap()))
            val model = Script(make(), finish)
            val session = session(model, maker)
            assertIs<AgentOutcome.Confirmation>(session.ask("Make this person a character in the game."))

            assertFailsWith<IllegalArgumentException> { session.confirm(consent = false) }
            val answer = assertIs<AgentOutcome.Answer>(session.decline())
            assertEquals("Your character is ready.", answer.text)
            assertEquals(emptyList(), maker.sent)
            val reply = model.toolReply()
            assertEquals("false", reply["ok"]?.jsonPrimitive?.content)
            assertTrue(reply["problem"]!!.jsonPrimitive.content.contains("chose not to send"))
            assertEquals(emptyList(), editor.project.images)
        }

    @Test
    fun withoutAPhotoOrAServerTheToolIsRefused() =
        runTest {
            val noPhoto = Script(make(), finish)
            assertIs<AgentOutcome.Answer>(session(noPhoto, FakeMaker(photo = false)).ask("Make me a character"))
            assertTrue(
                noPhoto
                    .toolReply()["problem"]!!
                    .jsonPrimitive.content
                    .contains("Attach a photo"),
            )
            assertTrue(
                (
                    noPhoto.requests
                        .first()
                        .messages
                        .first() as AgentMessage.User
                ).text.let { !it.contains(AgentSession.PHOTO_ATTACHED) },
            )

            val noServer = Script(make(), finish)
            assertIs<AgentOutcome.Answer>(session(noServer, null).ask("Make me a character"))
            assertTrue(
                noServer
                    .toolReply()["problem"]!!
                    .jsonPrimitive.content
                    .contains("sign in", ignoreCase = true),
            )
        }

    @Test
    fun badChoicesAreRefusedBeforeAskingTheCreator() =
        runTest {
            for (input in listOf("""{"styles":[]}""", """{"styles":["oil"]}""", """{"styles":["pixel"],"use":"chibi"}""")) {
                val model = Script(make(input), finish)
                assertIs<AgentOutcome.Answer>(session(model, FakeMaker()).ask("Make me a character"), input)
                assertEquals("false", model.toolReply()["ok"]?.jsonPrimitive?.content, input)
            }
        }

    @Test
    fun refusalsAndFailuresAreReportedAndStageNothing() =
        runTest {
            val refused = FakeMaker(result = CharacterResult(emptyList(), mapOf("pixel" to "The image model declined this photo.")))
            val first = Script(make("""{"styles":["pixel"]}"""), finish)
            val session = session(first, refused)
            assertIs<AgentOutcome.Confirmation>(session.ask("Make me a character"))
            assertIs<AgentOutcome.Answer>(session.confirm(consent = true))
            assertTrue(
                first
                    .toolReply()["problem"]!!
                    .jsonPrimitive.content
                    .contains("declined this photo"),
            )

            val failing = FakeMaker(failure = AccountException("You reached today's SpawnKit AI allowance.", 429))
            val second = Script(make("""{"styles":["pixel"]}"""), finish)
            val again = session(second, failing)
            assertIs<AgentOutcome.Confirmation>(again.ask("Make me a character"))
            assertIs<AgentOutcome.Answer>(again.confirm(consent = true))
            assertTrue(
                second
                    .toolReply()["problem"]!!
                    .jsonPrimitive.content
                    .contains("today's SpawnKit AI allowance"),
            )
            assertEquals(emptyList(), editor.project.images)
        }

    @Test
    fun aRefusedStyleFallsBackToOneThatWasMade() =
        runTest {
            val maker = FakeMaker(result = CharacterResult(listOf(cartoon), mapOf("pixel" to "The image model declined this photo.")))
            val model = Script(make(), finish)
            val session = session(model, maker)
            assertIs<AgentOutcome.Confirmation>(session.ask("Make me a character"))
            val proposal = assertIs<AgentOutcome.Proposal>(session.confirm(consent = true))
            val reply = model.toolReply()
            assertTrue(reply["refused"].toString().contains("declined"), reply.toString())
            val character =
                proposal.changes.project
                    .scene(scene)!!
                    .objects
                    .single { it.name == CharacterRecipe.NAME }
            assertEquals(false, character.component("graphics:sprite")!!.values["pixelated"])
        }
}
