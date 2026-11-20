package dev.spawnkit.program

import dev.spawnkit.runtime.AuthoringInfo
import dev.spawnkit.runtime.ComponentType
import dev.spawnkit.runtime.Runtime
import dev.spawnkit.runtime.RuntimeBuilder
import dev.spawnkit.runtime.StateCodecs

/** Small builders so tests read like the visual rules a frontend would produce. Node IDs name each block. */
internal object P {
    fun s(id: String) = SourceRef(id, id)

    fun int(
        v: Long,
        id: String = "lit",
    ) = Expr.Literal(IntegerValue(v), s(id))

    fun text(
        v: String,
        id: String = "lit",
    ) = Expr.Literal(TextValue(v), s(id))

    fun bool(
        v: Boolean,
        id: String = "lit",
    ) = Expr.Literal(BoolValue(v), s(id))

    fun v(
        name: String,
        id: String = "var:$name",
    ) = Expr.Variable(name, s(id))

    fun state(
        name: String,
        id: String = "state:$name",
    ) = Expr.GetState(name, s(id))

    fun op(
        op: BinaryOp,
        l: Expr,
        r: Expr,
        id: String = "op",
    ) = Expr.Binary(op, l, r, s(id))

    fun get(
        e: Expr,
        property: String,
        id: String = "get:$property",
    ) = Expr.GetProperty(e, property, s(id))

    fun self(id: String = "self") = Expr.Self(s(id))

    fun query(
        vararg properties: String,
        id: String = "query",
    ) = Expr.Query(properties.toList(), s(id))

    fun call(
        f: String,
        vararg args: Expr,
        id: String = "call:$f",
    ) = Expr.Call(f, args.toList(), s(id))

    fun host(
        f: String,
        vararg args: Expr,
        id: String = "host:$f",
    ) = Expr.CallHost(f, args.toList(), s(id))

    fun declare(
        name: String,
        value: Expr,
        id: String = "let:$name",
    ) = Stmt.Declare(name, value, s(id))

    fun assign(
        name: String,
        value: Expr,
        id: String = "set:$name",
    ) = Stmt.Assign(name, value, s(id))

    fun setState(
        name: String,
        value: Expr,
        id: String = "setstate:$name",
    ) = Stmt.SetState(name, value, s(id))

    fun setProp(
        e: Expr,
        property: String,
        value: Expr,
        id: String = "setprop:$property",
    ) = Stmt.SetProperty(e, property, value, s(id))

    fun ifThen(
        c: Expr,
        then: List<Stmt>,
        otherwise: List<Stmt> = emptyList(),
        id: String = "if",
    ) = Stmt.If(c, then, otherwise, s(id))

    fun loop(
        c: Expr,
        body: List<Stmt>,
        id: String = "while",
    ) = Stmt.While(c, body, s(id))

    fun each(
        name: String,
        list: Expr,
        body: List<Stmt>,
        id: String = "each:$name",
    ) = Stmt.ForEach(name, list, body, s(id))

    fun ret(
        value: Expr?,
        id: String = "return",
    ) = Stmt.Return(value, s(id))

    fun emit(
        event: String,
        payload: Expr?,
        id: String = "emit:$event",
    ) = Stmt.Emit(event, payload, s(id))

    fun wait(
        ticks: Expr,
        id: String = "wait",
    ) = Stmt.Wait(ticks, s(id))

    fun effect(
        name: String,
        payload: Expr,
        id: String = "effect:$name",
    ) = Stmt.RequestEffect(name, payload, s(id))

    fun eval(
        e: Expr,
        id: String = "eval",
    ) = Stmt.Evaluate(e, s(id))

    fun everyTick(
        vararg body: Stmt,
        id: String = "rule:tick",
    ) = Handler(Trigger.EveryTick, body.toList(), s(id))

    fun onEvent(
        event: String,
        vararg body: Stmt,
        id: String = "rule:$event",
    ) = Handler(Trigger.OnEvent(event), body.toList(), s(id))

    fun field(
        name: String,
        initial: ProgramValue,
    ) = StateField(name, initial, s("field:$name"))
}

/** A runtime with two integer components exposed as `value` and `score`, a host function, an event and an effect. */
internal class ProgramFixture {
    val builder = RuntimeBuilder()
    val value: ComponentType<Long> = builder.component("test:value", AuthoringInfo("Value"), StateCodecs.long)
    val score: ComponentType<Long> = builder.component("test:score", AuthoringInfo("Score"), StateCodecs.long)
    val host: ProgramHost =
        ProgramHostBuilder(ProgramLimits(fuelPerSegment = 5_000, allocationPerSegment = 2_000, maxListSize = 500))
            .property("value", value, IntegerType, AuthoringInfo("Value"), { IntegerValue(it) }, { _, v -> (v as IntegerValue).value })
            .property("score", score, IntegerType, AuthoringInfo("Score"), { IntegerValue(it) }, { _, v -> (v as IntegerValue).value })
            .function("bonus", listOf(IntegerType), IntegerType, AuthoringInfo("Bonus"), cost = 5) { call ->
                val n = (call.arguments[0] as IntegerValue).value
                if (n < 0) throw ProgramHostException("bonus needs a positive number")
                IntegerValue(n * 3)
            }.function("wrongType", emptyList(), IntegerType, AuthoringInfo("Broken")) { TextValue("oops") }
            .event("collected", IntegerType)
            .event("ping")
            .effect("analytics", IntegerType)
            .build()
    val feature = ProgramFeature(builder, host)
    val runtime: Runtime by lazy { builder.build() }

    fun prepare(program: Program): PreparedProgram =
        when (val result = prepareProgram(program, host)) {
            is PrepareResult.Ready -> result.program
            is PrepareResult.Invalid -> error("Unexpected diagnostics: ${result.diagnostics}")
        }
}
