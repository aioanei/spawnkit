package dev.spawnkit.editing

import dev.spawnkit.program.BinaryOp
import dev.spawnkit.program.BoolValue
import dev.spawnkit.program.DecimalValue
import dev.spawnkit.program.EntityValue
import dev.spawnkit.program.Expr
import dev.spawnkit.program.Handler
import dev.spawnkit.program.IntegerType
import dev.spawnkit.program.IntegerValue
import dev.spawnkit.program.ListType
import dev.spawnkit.program.ListValue
import dev.spawnkit.program.NothingValue
import dev.spawnkit.program.SourceRef
import dev.spawnkit.program.Stmt
import dev.spawnkit.program.TextValue
import dev.spawnkit.program.Trigger
import dev.spawnkit.runtime.RuntimeBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProjectPersistenceTest {
    private val source = SourceRef("custom-rule", "An advanced rule")
    private val self = Expr.Self(source)
    private val one = Expr.Literal(IntegerValue(1), source)
    private val expressions =
        listOf(
            one,
            Expr.Literal(DecimalValue(-0.0), source),
            Expr.Literal(BoolValue(true), source),
            Expr.Literal(TextValue("quote \" and newline\n"), source),
            Expr.Literal(NothingValue, source),
            Expr.Literal(ListValue(ListType(IntegerType), listOf(ListValue(IntegerType, listOf(IntegerValue(Long.MAX_VALUE))))), source),
            Expr.Variable("x", source),
            Expr.Binary(BinaryOp.Add, one, one, source),
            Expr.Not(Expr.Literal(BoolValue(false), source), source),
            Expr.Negate(one, source),
            Expr.Call("custom", listOf(one), source),
            Expr.CallHost("extension-function", listOf(self), source),
            Expr.GetState("counter", source),
            Expr.GetProperty(self, "custom-property", source),
            Expr.Query(listOf("custom-property"), source),
            self,
            Expr.ListOf(IntegerType, listOf(one), source),
            Expr.Append(Expr.ListOf(IntegerType, emptyList(), source), one, source),
            Expr.Size(Expr.ListOf(IntegerType, emptyList(), source), source),
            Expr.At(Expr.ListOf(IntegerType, listOf(one), source), one, source),
        )
    private val statements =
        expressions.map { Stmt.Evaluate(it, source) } +
            listOf(
                Stmt.Declare("x", one, source),
                Stmt.Assign("x", one, source),
                Stmt.SetState("counter", one, source),
                Stmt.SetProperty(self, "custom-property", one, source),
                Stmt.If(one, listOf(Stmt.Return(null, source)), listOf(Stmt.Return(one, source)), source),
                Stmt.While(one, emptyList(), source),
                Stmt.ForEach("x", one, emptyList(), source),
                Stmt.Emit("custom-event", null, source),
                Stmt.Emit("custom-event", one, source),
                Stmt.Wait(one, source),
                Stmt.Destroy(self, source),
                Stmt.RequestEffect("custom-effect", one, source),
            )
    private val project =
        Project(
            "My \"game\" — 漢字",
            listOf(
                SceneDoc(
                    "scene-2",
                    "First",
                    listOf(
                        ObjectDoc(
                            "obj-7",
                            "Child",
                            "obj-3",
                            listOf(
                                ComponentDoc(
                                    "extension:future",
                                    mapOf(
                                        "whole" to Long.MAX_VALUE,
                                        "negative" to Long.MIN_VALUE,
                                        "decimal" to 1.0,
                                        "negativeZero" to -0.0,
                                        "enabled" to true,
                                        "text" to "001",
                                        "future" to JsonPrimitive("opaque"),
                                    ),
                                ),
                            ),
                            listOf(
                                Handler(Trigger.EveryTick, statements, source),
                                Handler(Trigger.OnEvent("custom-event"), emptyList(), source),
                            ),
                        ),
                        ObjectDoc("obj-3", "Parent"),
                    ),
                ),
                SceneDoc("scene-1", "Second"),
            ),
            nextId = 100,
        )

    @Test
    fun completeWorkspaceAndAdvancedHandlersRoundTripWithoutInstalledExtensions() {
        val text = ProjectCodec.encode(project)
        val decoded = ProjectCodec.decode(text)
        assertEquals(project, decoded)
        assertEquals(text, ProjectCodec.encode(decoded))
        assertEquals(
            1.0.toBits(),
            (
                decoded.scenes[0]
                    .objects[0]
                    .components[0]
                    .values
                    .getValue("decimal") as Double
            ).toBits(),
        )
        assertEquals(
            (-0.0).toBits(),
            (
                (
                    decoded.scenes[0]
                        .objects[0]
                        .rules[0]
                        .body[1] as Stmt.Evaluate
                ).expr as Expr.Literal
            ).let {
                (it.value as DecimalValue).value.toBits()
            },
        )
    }

    @Test
    fun malformedFutureAndStructurallyInvalidFilesFailExplicitly() {
        val malformed = assertFailsWith<IllegalArgumentException> { ProjectCodec.decode("{broken") }
        assertTrue(malformed.message.orEmpty().contains("damaged"))
        val text = ProjectCodec.encode(project)
        assertFailsWith<IllegalArgumentException> { ProjectCodec.decode(text.replace("\"schemaVersion\":2", "\"schemaVersion\":999")) }
        assertFailsWith<IllegalArgumentException> {
            ProjectCodec.decode(
                text.replace("\"kind\":\"evaluate\"", "\"kind\":\"future-instruction\""),
            )
        }
        assertFailsWith<IllegalArgumentException> { ProjectCodec.encode(project.copy(scenes = emptyList())) }
        assertFailsWith<IllegalArgumentException> { ProjectCodec.decode("[".repeat(300) + "0" + "]".repeat(300)) }
        assertFailsWith<IllegalArgumentException> { ProjectCodec.decode(" ".repeat(ProjectCodec.MAX_FILE_CHARS + 1)) }
        val chain = (0..130).map { ObjectDoc("obj-$it", "Object $it", if (it == 0) null else "obj-${it - 1}") }
        assertFailsWith<IllegalArgumentException> { ProjectCodec.encode(Project("Too deep", listOf(SceneDoc("scene", "Scene", chain)))) }
    }

    @Test
    fun runtimeHandlesAndNonFiniteValuesAreNeverWrittenAsPortableProjectReferences() {
        val handle = RuntimeBuilder().build().createWorld().spawn()
        val literal = Stmt.Evaluate(Expr.Literal(EntityValue(handle), source), source)
        val invalid =
            Project(
                "Not portable",
                listOf(
                    SceneDoc(
                        "scene",
                        "Scene",
                        listOf(ObjectDoc("obj", "Object", rules = listOf(Handler(Trigger.EveryTick, listOf(literal), source)))),
                    ),
                ),
            )
        assertFailsWith<IllegalArgumentException> { ProjectCodec.encode(invalid) }
        val component = ComponentDoc("extension:custom", mapOf("value" to Double.NaN))
        assertFailsWith<IllegalArgumentException> {
            ProjectCodec.encode(
                Project("Invalid", listOf(SceneDoc("scene", "Scene", listOf(ObjectDoc("obj", "Object", components = listOf(component)))))),
            )
        }
    }

    @Test
    fun failedWritesKeepTheCurrentDraftAndCorruptionFallsBackToThePreviousDraft() {
        val store = TestDraftStore()
        val journal = ProjectJournal(store)
        val first = Project.blank("First")
        val second = Project.blank("Second")
        journal.save(first)
        store.failWrites = true
        assertFailsWith<IllegalStateException> { journal.save(second) }
        assertEquals(first, assertIs<RecoveryResult.Found>(journal.load()).project)
        store.failWrites = false
        journal.save(second)
        store.failCurrentRead = true
        assertEquals(first, assertIs<RecoveryResult.Found>(journal.load()).project)
        store.failCurrentRead = false
        store.values[ProjectJournal.CURRENT] = "{interrupted"
        val recovered = assertIs<RecoveryResult.Found>(journal.load())
        assertTrue(recovered.fromPrevious)
        assertEquals(first, recovered.project)
        journal.save(first)
        assertEquals("{interrupted", store.values[ProjectJournal.DAMAGED])
        assertEquals(first, assertIs<RecoveryResult.Found>(journal.load()).project)
    }

    @Test
    fun reopeningKeepsRetiredIdsAndUnavailableCapabilitiesWithoutRunningThem() {
        val runtime = RuntimeBuilder().build()
        val editor = Editor(runtime)
        val first = assertIs<OperationResult.Applied>(editor.apply(EditOperation.AddObject("scene-1", "Object"))).createdIds.single()
        editor.undo()
        val reopened = Editor(runtime, ProjectCodec.decode(ProjectCodec.encode(editor.saveSnapshot())))
        val next = assertIs<OperationResult.Applied>(reopened.apply(EditOperation.AddObject("scene-1", "Object"))).createdIds.single()
        assertTrue(first != next)
        val decoded = ProjectCodec.decode(ProjectCodec.encode(project))
        val loaded = Editor(runtime, decoded)
        assertTrue(decoded.capabilityProblems(runtime).first().contains("Child"))
        assertFailsWith<IllegalArgumentException> { loaded.buildWorld("scene-2") }
        loaded.buildPreview("scene-2")
        assertEquals(decoded, loaded.project)
        loaded.apply(EditOperation.RenameProject("New title"))
        assertEquals("New title", loaded.project.name)
        loaded.undo()
        assertEquals(decoded, loaded.project)
    }

    @Test
    fun missingOrUnreadableRecoveryIsExplainedAndDoesNotAlterStorage() {
        val store = TestDraftStore()
        val journal = ProjectJournal(store)
        assertIs<RecoveryResult.Empty>(journal.load())
        store.values[ProjectJournal.CURRENT] = "bad data"
        val before = store.values.toMap()
        assertIs<RecoveryResult.Unreadable>(journal.load())
        assertEquals(before, store.values)
    }

    private class TestDraftStore : DraftStore {
        val values = mutableMapOf<String, String>()
        var failWrites = false
        var failCurrentRead = false

        override fun read(key: String): String? {
            check(!failCurrentRead || key != ProjectJournal.CURRENT) { "Cannot read current draft" }
            return values[key]
        }

        override fun write(
            key: String,
            text: String,
        ) {
            check(!failWrites) { "Disk full" }
            values[key] = text
        }
    }
}
