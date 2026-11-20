package dev.spawnkit.program

/** A preparation error located at its authoring [source]. */
public data class Diagnostic(
    /** Offending node. */
    public val source: SourceRef,
    /** Plain-language explanation. */
    public val message: String,
)

/** Result of [prepareProgram]: either an executable program or the diagnostics that prevent execution. */
public sealed interface PrepareResult {
    /** The program type-checked and compiled. */
    public data class Ready(
        /** Executable program. */
        public val program: PreparedProgram,
    ) : PrepareResult

    /** The program has errors; nothing executes. */
    public data class Invalid(
        /** All detected errors, in source order. */
        public val diagnostics: List<Diagnostic>,
    ) : PrepareResult
}

/**
 * A type-checked program lowered to resumable bytecode for one [ProgramHost]. Suspended execution is plain data
 * (unit, program counter, locals, operand stack), which is what makes checkpoints of waiting behaviors possible.
 */
public class PreparedProgram internal constructor(
    /** Source program. */
    public val program: Program,
    internal val host: ProgramHost,
    internal val units: List<CodeUnit>,
    internal val functionCount: Int,
    internal val stateInitial: List<ProgramValue>,
    internal val stateNames: List<String>,
) {
    /**
     * Deterministic fingerprint of the compiled code plus its declarations (state fields and initial values,
     * handler triggers, function signatures), checked when checkpointed instances are restored.
     */
    public val fingerprint: Long =
        units.fold(mix(FNV_OFFSET, signature(program))) { hash, unit -> mix(hash, unit.describe()) }

    internal fun handlerUnit(index: Int): Int = functionCount + index

    internal val handlers: List<Handler> get() = program.handlers

    private companion object {
        const val FNV_OFFSET = -3750763034362895579L
        const val FNV_PRIME = 1099511628211L

        fun mix(
            start: Long,
            text: String,
        ): Long {
            var hash = start
            for (char in text) {
                hash = (hash xor char.code.toLong()) * FNV_PRIME
            }
            return hash
        }
    }
}

/** Type-checks [program] against [host] and compiles it, or returns every diagnostic found. */
public fun prepareProgram(
    program: Program,
    host: ProgramHost,
): PrepareResult = ProgramCompiler(program, host).compile()

/** One function or handler body. Locals hold parameters first. */
internal class CodeUnit(
    val name: String,
    val parameterCount: Int,
    var localCount: Int,
    val code: List<Op>,
    val sources: List<SourceRef>,
    val returnsValue: Boolean,
) {
    /** Canonical text for fingerprints; avoids platform-specific number formatting. */
    fun describe(): String = "$name/$parameterCount/$localCount/$returnsValue:" + code.joinToString(";") { canonical(it) }
}

/** Bytecode instructions. A stack machine keeps continuations as plain data. */
internal sealed interface Op {
    data class Push(
        val value: ProgramValue,
    ) : Op

    data class Load(
        val slot: Int,
    ) : Op

    data class Store(
        val slot: Int,
    ) : Op

    data class LoadState(
        val field: Int,
    ) : Op

    data class StoreState(
        val field: Int,
    ) : Op

    data class Arithmetic(
        val op: BinaryOp,
    ) : Op

    data object Negate : Op

    data object Not : Op

    data class Jump(
        var target: Int,
    ) : Op

    data class JumpIfFalse(
        var target: Int,
    ) : Op

    data class Call(
        val unit: Int,
        val argumentCount: Int,
    ) : Op

    data class CallHost(
        val function: Int,
        val argumentCount: Int,
    ) : Op

    data object Return : Op

    data object End : Op

    data object Pop : Op

    data class MakeList(
        val element: ProgramType,
        val count: Int,
    ) : Op

    data object Append : Op

    data object Size : Op

    data object At : Op

    data class Query(
        val properties: List<Int>,
    ) : Op

    data class GetProperty(
        val property: Int,
    ) : Op

    data class SetProperty(
        val property: Int,
    ) : Op

    data object Self : Op

    data class Emit(
        val event: String,
        val hasPayload: Boolean,
    ) : Op

    data class Effect(
        val effect: String,
    ) : Op

    data object Wait : Op
}

private class Scope(
    val parent: Scope?,
) {
    val names = mutableMapOf<String, Pair<Int, ProgramType>>()

    fun find(name: String): Pair<Int, ProgramType>? = names[name] ?: parent?.find(name)
}

private class UnitBuilder(
    val name: String,
    val returns: ProgramType?,
) {
    val code = mutableListOf<Op>()
    val sources = mutableListOf<SourceRef>()
    var nextSlot = 0
    var maxSlot = 0

    fun emit(
        op: Op,
        source: SourceRef,
    ): Int {
        code += op
        sources += source
        return code.size - 1
    }

    fun allocate(): Int = nextSlot++.also { maxSlot = maxOf(maxSlot, nextSlot) }
}

private class ProgramCompiler(
    private val program: Program,
    private val host: ProgramHost,
) {
    private val diagnostics = mutableListOf<Diagnostic>()
    private val functionIndex = mutableMapOf<String, Int>()
    private val stateIndex = mutableMapOf<String, Int>()
    private lateinit var unit: UnitBuilder
    private var depth = 0

    fun compile(): PrepareResult {
        program.state.forEachIndexed { index, field ->
            if (field.name in stateIndex) error(field.source, "State field '${field.name}' is declared twice")
            stateIndex[field.name] = index
            if (containsEntity(field.initial)) error(field.source, "State field '${field.name}' cannot start with an object")
            if (!conforms(field.initial, field.initial.type)) {
                error(field.source, "State field '${field.name}' starts with list items of the wrong type")
            }
            if (longestText(field.initial) > host.limits.maxTextLength) {
                error(field.source, "State field '${field.name}' starts with text longer than ${host.limits.maxTextLength} characters")
            }
        }
        program.functions.forEachIndexed { index, function ->
            if (function.name in functionIndex) error(function.source, "Function '${function.name}' is declared twice")
            functionIndex[function.name] = index
        }
        val units = mutableListOf<CodeUnit>()
        for (function in program.functions) units += compileFunction(function)
        for ((index, handler) in program.handlers.withIndex()) units += compileHandler(handler, index)
        if (diagnostics.isNotEmpty()) return PrepareResult.Invalid(diagnostics.toList())
        return PrepareResult.Ready(
            PreparedProgram(
                program,
                host,
                units,
                program.functions.size,
                program.state.map { it.initial },
                program.state.map { it.name },
            ),
        )
    }

    private fun compileFunction(function: FunctionDef): CodeUnit {
        unit = UnitBuilder(function.name, function.returns)
        val scope = Scope(null)
        for (parameter in function.parameters) {
            if (parameter.name in scope.names) error(function.source, "Parameter '${parameter.name}' is declared twice")
            scope.names[parameter.name] = unit.allocate() to parameter.type
        }
        block(function.body, scope)
        if (function.returns == NothingType) {
            unit.emit(Op.Push(NothingValue), function.source)
            unit.emit(Op.Return, function.source)
        } else if (!alwaysReturns(function.body)) {
            error(function.source, "Function '${function.name}' must return a ${describe(function.returns)} on every path")
        }
        return finish(function.parameters.size, returnsValue = true)
    }

    private fun compileHandler(
        handler: Handler,
        index: Int,
    ): CodeUnit {
        unit = UnitBuilder("handler#$index", null)
        val scope = Scope(null)
        var parameters = 0
        val trigger = handler.trigger
        if (trigger is Trigger.OnEvent) {
            val event = host.eventsByName[trigger.event]
            if (event == null) {
                error(handler.source, "Unknown event '${trigger.event}'")
            } else if (event.payload != NothingType) {
                scope.names["payload"] = unit.allocate() to event.payload
                parameters = 1
            }
        }
        block(handler.body, scope)
        unit.emit(Op.End, handler.source)
        return finish(parameters, returnsValue = false)
    }

    private fun finish(
        parameters: Int,
        returnsValue: Boolean,
    ): CodeUnit = CodeUnit(unit.name, parameters, unit.maxSlot, unit.code.toList(), unit.sources.toList(), returnsValue)

    private fun block(
        statements: List<Stmt>,
        parent: Scope,
    ) {
        val scope = Scope(parent)
        val savedSlot = unit.nextSlot
        statements.forEach { statement(it, scope) }
        unit.nextSlot = savedSlot
    }

    private fun statement(
        stmt: Stmt,
        scope: Scope,
    ) {
        if (++depth > MAX_NESTING) {
            if (depth == MAX_NESTING + 1) error(stmt.source, "Rules are nested too deeply (more than $MAX_NESTING levels)")
            depth--
            return
        }
        try {
            statementBody(stmt, scope)
        } finally {
            depth--
        }
    }

    private fun statementBody(
        stmt: Stmt,
        scope: Scope,
    ) {
        val src = stmt.source
        when (stmt) {
            is Stmt.Declare -> {
                val type = expression(stmt.value, scope)
                if (stmt.name in scope.names) error(src, "'${stmt.name}' is already declared in this block")
                if (type == NothingType) error(src, "'${stmt.name}' cannot hold nothing")
                val slot = unit.allocate()
                scope.names[stmt.name] = slot to type
                unit.emit(Op.Store(slot), src)
            }

            is Stmt.Assign -> {
                val target = scope.find(stmt.name)
                val type = expression(stmt.value, scope)
                if (target == null) {
                    error(src, "Unknown variable '${stmt.name}'")
                } else {
                    expect(type, target.second, src, "'${stmt.name}'")
                    unit.emit(Op.Store(target.first), src)
                }
            }

            is Stmt.SetState -> {
                val index = stateIndex[stmt.field]
                val type = expression(stmt.value, scope)
                if (index == null) {
                    error(src, "Unknown state field '${stmt.field}'")
                } else {
                    expect(type, program.state[index].initial.type, src, "state '${stmt.field}'")
                    unit.emit(Op.StoreState(index), src)
                }
            }

            is Stmt.SetProperty -> {
                expect(expression(stmt.entity, scope), EntityType, src, "the target")
                val type = expression(stmt.value, scope)
                val index = host.propertyIndex[stmt.property]
                if (index == null) {
                    error(src, "Unknown property '${stmt.property}'")
                } else {
                    expect(type, host.properties[index].type, src, "property '${stmt.property}'")
                    unit.emit(Op.SetProperty(index), src)
                }
            }

            is Stmt.If -> {
                expect(expression(stmt.condition, scope), BoolType, src, "the condition")
                val toElse = Op.JumpIfFalse(-1)
                unit.emit(toElse, src)
                block(stmt.then, scope)
                if (stmt.otherwise.isEmpty()) {
                    toElse.target = unit.code.size
                } else {
                    val toEnd = Op.Jump(-1)
                    unit.emit(toEnd, src)
                    toElse.target = unit.code.size
                    block(stmt.otherwise, scope)
                    toEnd.target = unit.code.size
                }
            }

            is Stmt.While -> {
                val start = unit.code.size
                expect(expression(stmt.condition, scope), BoolType, src, "the loop condition")
                val exit = Op.JumpIfFalse(-1)
                unit.emit(exit, src)
                block(stmt.body, scope)
                unit.emit(Op.Jump(start), src)
                exit.target = unit.code.size
            }

            is Stmt.ForEach -> {
                forEach(stmt, scope)
            }

            is Stmt.Return -> {
                val returns = unit.returns
                if (returns == null) {
                    if (stmt.value != null) error(src, "A rule cannot return a value")
                    unit.emit(Op.End, src)
                } else if (stmt.value == null) {
                    if (returns != NothingType) error(src, "This function must return a ${describe(returns)}")
                    unit.emit(Op.Push(NothingValue), src)
                    unit.emit(Op.Return, src)
                } else {
                    expect(expression(stmt.value, scope), returns, src, "the returned value")
                    unit.emit(Op.Return, src)
                }
            }

            is Stmt.Evaluate -> {
                expression(stmt.expr, scope)
                unit.emit(Op.Pop, src)
            }

            is Stmt.Emit -> {
                val event = host.eventsByName[stmt.event]
                if (event == null) {
                    error(src, "Unknown event '${stmt.event}'")
                    stmt.payload?.let { expression(it, scope) }
                } else if (event.payload == NothingType) {
                    if (stmt.payload != null) error(src, "Event '${stmt.event}' carries no value")
                    unit.emit(Op.Emit(stmt.event, hasPayload = false), src)
                } else if (stmt.payload == null) {
                    error(src, "Event '${stmt.event}' needs a ${describe(event.payload)} value")
                } else {
                    expect(expression(stmt.payload, scope), event.payload, src, "the event value")
                    unit.emit(Op.Emit(stmt.event, hasPayload = true), src)
                }
            }

            is Stmt.Wait -> {
                expect(expression(stmt.ticks, scope), IntegerType, src, "the wait duration")
                unit.emit(Op.Wait, src)
            }

            is Stmt.RequestEffect -> {
                val effect = host.effectsByName[stmt.effect]
                val type = expression(stmt.payload, scope)
                if (effect == null) {
                    error(src, "Unknown effect '${stmt.effect}'")
                } else {
                    expect(type, effect.payload, src, "the effect value")
                    unit.emit(Op.Effect(stmt.effect), src)
                }
            }
        }
    }

    /** Lowers `for x in list` to an index loop over hidden locals holding the evaluated list snapshot. */
    private fun forEach(
        stmt: Stmt.ForEach,
        parent: Scope,
    ) {
        val src = stmt.source
        val listType = expression(stmt.list, parent)
        val element = (listType as? ListType)?.element
        if (element == null) error(src, "Can only repeat for each item of a list, not ${describe(listType)}")
        val scope = Scope(parent)
        val saved = unit.nextSlot
        val listSlot = unit.allocate()
        val indexSlot = unit.allocate()
        unit.emit(Op.Store(listSlot), src)
        unit.emit(Op.Push(IntegerValue(0)), src)
        unit.emit(Op.Store(indexSlot), src)
        val start = unit.code.size
        unit.emit(Op.Load(indexSlot), src)
        unit.emit(Op.Load(listSlot), src)
        unit.emit(Op.Size, src)
        unit.emit(Op.Arithmetic(BinaryOp.Less), src)
        val exit = Op.JumpIfFalse(-1)
        unit.emit(exit, src)
        val itemSlot = unit.allocate()
        scope.names[stmt.name] = itemSlot to (element ?: NothingType)
        unit.emit(Op.Load(listSlot), src)
        unit.emit(Op.Load(indexSlot), src)
        unit.emit(Op.At, src)
        unit.emit(Op.Store(itemSlot), src)
        block(stmt.body, scope)
        unit.emit(Op.Load(indexSlot), src)
        unit.emit(Op.Push(IntegerValue(1)), src)
        unit.emit(Op.Arithmetic(BinaryOp.Add), src)
        unit.emit(Op.Store(indexSlot), src)
        unit.emit(Op.Jump(start), src)
        exit.target = unit.code.size
        unit.nextSlot = saved
    }

    /** Emits code leaving one value on the stack and returns its static type. */
    private fun expression(
        expr: Expr,
        scope: Scope,
    ): ProgramType {
        if (++depth > MAX_NESTING) {
            if (depth == MAX_NESTING + 1) error(expr.source, "Rules are nested too deeply (more than $MAX_NESTING levels)")
            depth--
            unit.emit(Op.Push(NothingValue), expr.source)
            return NothingType
        }
        try {
            return expressionBody(expr, scope)
        } finally {
            depth--
        }
    }

    private fun expressionBody(
        expr: Expr,
        scope: Scope,
    ): ProgramType {
        val src = expr.source
        return when (expr) {
            is Expr.Literal -> {
                if (containsEntity(expr.value)) error(src, "Object constants are not allowed; use a query or Self")
                if (!conforms(expr.value, expr.value.type)) error(src, "This list contains items of the wrong type")
                if (longestText(expr.value) >
                    host.limits.maxTextLength
                ) {
                    error(src, "This text is longer than ${host.limits.maxTextLength} characters")
                }
                unit.emit(Op.Push(expr.value), src)
                expr.value.type
            }

            is Expr.Variable -> {
                val found = scope.find(expr.name)
                if (found == null) {
                    error(src, "Unknown variable '${expr.name}'")
                    unit.emit(Op.Push(NothingValue), src)
                    NothingType
                } else {
                    unit.emit(Op.Load(found.first), src)
                    found.second
                }
            }

            is Expr.Binary -> {
                binary(expr, scope)
            }

            is Expr.Not -> {
                expect(expression(expr.operand, scope), BoolType, src, "'not'")
                unit.emit(Op.Not, src)
                BoolType
            }

            is Expr.Negate -> {
                val type = expression(expr.operand, scope)
                if (type != IntegerType && type != DecimalType) error(src, "Only numbers can be negated, not ${describe(type)}")
                unit.emit(Op.Negate, src)
                type
            }

            is Expr.Call -> {
                call(expr, scope)
            }

            is Expr.CallHost -> {
                val index = host.functionIndex[expr.function]
                val types = expr.arguments.map { expression(it, scope) }
                if (index == null) {
                    error(src, "Unknown action '${expr.function}'")
                    NothingType
                } else {
                    val function = host.functions[index]
                    checkArguments(function.name, function.parameters, types, src)
                    unit.emit(Op.CallHost(index, types.size), src)
                    function.returns
                }
            }

            is Expr.GetState -> {
                val index = stateIndex[expr.field]
                if (index == null) {
                    error(src, "Unknown state field '${expr.field}'")
                    unit.emit(Op.Push(NothingValue), src)
                    NothingType
                } else {
                    unit.emit(Op.LoadState(index), src)
                    program.state[index].initial.type
                }
            }

            is Expr.GetProperty -> {
                expect(expression(expr.entity, scope), EntityType, src, "the object")
                val index = host.propertyIndex[expr.property]
                if (index == null) {
                    error(src, "Unknown property '${expr.property}'")
                    NothingType
                } else {
                    unit.emit(Op.GetProperty(index), src)
                    host.properties[index].type
                }
            }

            is Expr.Query -> {
                if (expr.properties.isEmpty()) error(src, "A query needs at least one property")
                val indices =
                    expr.properties.mapNotNull { name ->
                        host.propertyIndex[name] ?: null.also { error(src, "Unknown property '$name'") }
                    }
                unit.emit(Op.Query(indices), src)
                ListType(EntityType)
            }

            is Expr.Self -> {
                unit.emit(Op.Self, src)
                EntityType
            }

            is Expr.ListOf -> {
                expr.items.forEach { expect(expression(it, scope), expr.element, it.source, "the list item") }
                unit.emit(Op.MakeList(expr.element, expr.items.size), src)
                ListType(expr.element)
            }

            is Expr.Append -> {
                val listType = expression(expr.list, scope)
                val itemType = expression(expr.item, scope)
                if (listType !is ListType) {
                    error(src, "Can only add items to a list, not ${describe(listType)}")
                } else {
                    expect(itemType, listType.element, src, "the added item")
                }
                unit.emit(Op.Append, src)
                listType
            }

            is Expr.Size -> {
                val listType = expression(expr.list, scope)
                if (listType !is ListType) error(src, "Only lists have a size, not ${describe(listType)}")
                unit.emit(Op.Size, src)
                IntegerType
            }

            is Expr.At -> {
                val listType = expression(expr.list, scope)
                expect(expression(expr.index, scope), IntegerType, src, "the position")
                unit.emit(Op.At, src)
                (listType as? ListType)?.element ?: NothingType.also {
                    error(src, "Only lists have items, not ${describe(listType)}")
                }
            }
        }
    }

    private fun binary(
        expr: Expr.Binary,
        scope: Scope,
    ): ProgramType {
        val src = expr.source
        if (expr.op == BinaryOp.And || expr.op == BinaryOp.Or) {
            // Short circuit: And = if left then right else false; Or = if left then true else right.
            expect(expression(expr.left, scope), BoolType, src, "'${expr.op.name.lowercase()}'")
            val toRight = Op.JumpIfFalse(-1)
            unit.emit(toRight, src)
            if (expr.op == BinaryOp.And) {
                expect(expression(expr.right, scope), BoolType, src, "'and'")
                val toEnd = Op.Jump(-1)
                unit.emit(toEnd, src)
                toRight.target = unit.code.size
                unit.emit(Op.Push(BoolValue(false)), src)
                toEnd.target = unit.code.size
            } else {
                unit.emit(Op.Push(BoolValue(true)), src)
                val toEnd = Op.Jump(-1)
                unit.emit(toEnd, src)
                toRight.target = unit.code.size
                expect(expression(expr.right, scope), BoolType, src, "'or'")
                toEnd.target = unit.code.size
            }
            return BoolType
        }
        val left = expression(expr.left, scope)
        val right = expression(expr.right, scope)
        unit.emit(Op.Arithmetic(expr.op), src)
        if (left != right) {
            error(src, "Cannot combine ${describe(left)} and ${describe(right)} with ${expr.op.name}")
            return if (expr.op in COMPARISONS) BoolType else left
        }
        val numeric = left == IntegerType || left == DecimalType
        return when (expr.op) {
            BinaryOp.Equal, BinaryOp.NotEqual -> {
                BoolType
            }

            BinaryOp.Less, BinaryOp.LessOrEqual, BinaryOp.Greater, BinaryOp.GreaterOrEqual -> {
                if (!numeric) error(src, "Only numbers can be compared, not ${describe(left)}")
                BoolType
            }

            BinaryOp.Add -> {
                if (!numeric && left != TextType) error(src, "Cannot add ${describe(left)} values")
                left
            }

            BinaryOp.Remainder -> {
                if (left != IntegerType) error(src, "Remainder needs whole numbers, not ${describe(left)}")
                left
            }

            else -> {
                if (!numeric) error(src, "Arithmetic needs numbers, not ${describe(left)}")
                left
            }
        }
    }

    private fun call(
        expr: Expr.Call,
        scope: Scope,
    ): ProgramType {
        val types = expr.arguments.map { expression(it, scope) }
        val index = functionIndex[expr.function]
        if (index == null) {
            error(expr.source, "Unknown function '${expr.function}'")
            unit.emit(Op.Push(NothingValue), expr.source)
            return NothingType
        }
        val function = program.functions[index]
        checkArguments(function.name, function.parameters.map { it.type }, types, expr.source)
        unit.emit(Op.Call(index, types.size), expr.source)
        return function.returns
    }

    private fun checkArguments(
        name: String,
        expected: List<ProgramType>,
        actual: List<ProgramType>,
        source: SourceRef,
    ) {
        if (expected.size != actual.size) {
            error(source, "'$name' needs ${expected.size} value(s) but got ${actual.size}")
            return
        }
        expected.zip(actual).forEachIndexed { i, (want, got) -> expect(got, want, source, "value ${i + 1} of '$name'") }
    }

    private fun expect(
        actual: ProgramType,
        expected: ProgramType,
        source: SourceRef,
        what: String,
    ) {
        if (actual != expected) error(source, "Expected ${describe(expected)} for $what but found ${describe(actual)}")
    }

    private fun error(
        source: SourceRef,
        message: String,
    ) {
        diagnostics += Diagnostic(source, message)
    }

    private fun alwaysReturns(statements: List<Stmt>): Boolean =
        statements.any { stmt ->
            stmt is Stmt.Return || (stmt is Stmt.If && alwaysReturns(stmt.then) && alwaysReturns(stmt.otherwise))
        }

    private companion object {
        const val MAX_NESTING = 64

        val COMPARISONS =
            setOf(
                BinaryOp.Equal,
                BinaryOp.NotEqual,
                BinaryOp.Less,
                BinaryOp.LessOrEqual,
                BinaryOp.Greater,
                BinaryOp.GreaterOrEqual,
            )
    }
}

/** Friendly type names for diagnostics. */
internal fun describe(type: ProgramType): String =
    when (type) {
        IntegerType -> "whole number"
        DecimalType -> "decimal number"
        BoolType -> "yes/no value"
        TextType -> "text"
        EntityType -> "object"
        NothingType -> "nothing"
        is ListType -> "list of ${describe(type.element)}"
    }

/** Platform-independent instruction text: decimals are written as raw IEEE-754 bits. */
private fun canonical(op: Op): String =
    when (op) {
        is Op.Push -> "Push(${canonical(op.value)})"
        else -> op.toString()
    }

private fun canonical(value: ProgramValue): String =
    when (value) {
        is DecimalValue -> "d:${value.value.toRawBits()}"
        is ListValue -> "l:${value.element}[${value.items.joinToString(",") { canonical(it) }}]"
        is TextValue -> "t:${value.value.length}:${value.value}"
        else -> value.toString()
    }

/** Whether [value] structurally has [type], including every nested list item. */
internal fun conforms(
    value: ProgramValue,
    type: ProgramType,
): Boolean =
    when (value) {
        is ListValue -> type is ListType && value.element == type.element && value.items.all { conforms(it, type.element) }
        else -> value.type == type
    }

/** Whether [value] contains an entity anywhere; entity constants are not allowed in program source. */
internal fun containsEntity(value: ProgramValue): Boolean =
    value is EntityValue || (value is ListValue && value.items.any(::containsEntity))

/** Canonical declaration text: state fields with initial values, handler triggers and function signatures. */
private fun signature(program: Program): String =
    buildString {
        append(program.id).append('|')
        program.state.forEach {
            append("s:")
                .append(it.name)
                .append('=')
                .append(canonical(it.initial))
                .append(';')
        }
        program.handlers.forEach { handler ->
            val trigger = handler.trigger
            append("h:").append(if (trigger is Trigger.OnEvent) "event:" + trigger.event else "tick").append(';')
        }
        program.functions.forEach { function ->
            append("f:").append(function.name).append('(')
            append(function.parameters.joinToString(",") { "${it.name}:${it.type}" }).append("):").append(function.returns).append(';')
        }
    }

/** Length of the longest text anywhere in [value]. */
internal fun longestText(value: ProgramValue): Int =
    when (value) {
        is TextValue -> value.value.length
        is ListValue -> value.items.maxOfOrNull(::longestText) ?: 0
        else -> 0
    }
