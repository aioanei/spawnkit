package dev.spawnkit.program

import dev.spawnkit.program.P.declare
import dev.spawnkit.program.P.everyTick
import dev.spawnkit.program.P.field
import dev.spawnkit.program.P.get
import dev.spawnkit.program.P.host
import dev.spawnkit.program.P.ifThen
import dev.spawnkit.program.P.int
import dev.spawnkit.program.P.op
import dev.spawnkit.program.P.ret
import dev.spawnkit.program.P.s
import dev.spawnkit.program.P.self
import dev.spawnkit.program.P.setState
import dev.spawnkit.program.P.text
import dev.spawnkit.program.P.v
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProgramCompileTest {
    private val fixture = ProgramFixture()

    private fun diagnostics(program: Program): List<Diagnostic> =
        assertIs<PrepareResult.Invalid>(prepareProgram(program, fixture.host)).diagnostics

    @Test
    fun typeErrorsAreReportedAtTheirSourceNodes() {
        val found =
            diagnostics(
                Program(
                    "broken",
                    state = listOf(field("count", IntegerValue(0))),
                    handlers =
                        listOf(
                            everyTick(
                                declare("x", op(BinaryOp.Add, int(1), text("a"), id = "block:add")),
                                setState("count", text("many"), id = "block:count"),
                                P.eval(v("missing", id = "block:missing")),
                                P.eval(host("bonus", text("no"), id = "block:bonus")),
                                P.eval(get(self(), "nope", id = "block:prop")),
                            ),
                        ),
                ),
            )
        val nodes = found.map { it.source.nodeId }
        assertEquals(listOf("block:add", "block:count", "block:missing", "block:bonus", "block:prop"), nodes)
        assertTrue(found[0].message.contains("whole number") && found[0].message.contains("text"), found[0].message)
        assertTrue(found[2].message.contains("Unknown variable 'missing'"))
    }

    @Test
    fun functionsMustReturnOnEveryPathAndUseDeclaredArity() {
        val found =
            diagnostics(
                Program(
                    "returns",
                    functions =
                        listOf(
                            FunctionDef(
                                "half",
                                listOf(Parameter("n", IntegerType)),
                                IntegerType,
                                listOf(ifThen(op(BinaryOp.Greater, v("n"), int(0)), listOf(ret(v("n"))))),
                                s("fn:half"),
                            ),
                        ),
                    handlers = listOf(everyTick(P.eval(P.call("half", int(1), int(2), id = "block:call")))),
                ),
            )
        assertEquals(listOf("fn:half", "block:call"), found.map { it.source.nodeId })
    }

    @Test
    fun eventsAndEffectsAreCheckedAgainstTheHost() {
        val found =
            diagnostics(
                Program(
                    "messages",
                    handlers =
                        listOf(
                            everyTick(
                                P.emit("collected", null, id = "block:needs-payload"),
                                P.emit("ping", int(1), id = "block:no-payload"),
                                P.effect("analytics", text("x"), id = "block:effect"),
                            ),
                            P.onEvent("unknown", id = "rule:unknown"),
                        ),
                ),
            )
        assertEquals(listOf("block:needs-payload", "block:no-payload", "block:effect", "rule:unknown"), found.map { it.source.nodeId })
    }

    @Test
    fun fingerprintsDistinguishDifferentCode() {
        val a = fixture.prepare(Program("p", handlers = listOf(everyTick(declare("x", int(1))))))
        val b = fixture.prepare(Program("p", handlers = listOf(everyTick(declare("x", int(2))))))
        val again = fixture.prepare(Program("p", handlers = listOf(everyTick(declare("x", int(1))))))
        assertNotEquals(a.fingerprint, b.fingerprint)
        assertEquals(a.fingerprint, again.fingerprint)
    }
}
