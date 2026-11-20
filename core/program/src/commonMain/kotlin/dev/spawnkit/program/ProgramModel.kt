package dev.spawnkit.program

import dev.spawnkit.runtime.EntityHandle

/**
 * Where a program element came from in its authoring frontend, such as a visual rule block. Diagnostics and runtime
 * faults point here so editors can highlight the affected object, rule or block in the creator's own terms.
 */
public data class SourceRef(
    /** Stable frontend node identifier. */
    public val nodeId: String,
    /** Human-readable description of the node, for messages. */
    public val label: String = nodeId,
)

/** Static value types of the portable program language. */
public sealed interface ProgramType

/** Signed 64-bit integer with wrapping overflow. */
public data object IntegerType : ProgramType

/** IEEE-754 double; programs only use `+ - * /` and comparisons. */
public data object DecimalType : ProgramType

/** Boolean. */
public data object BoolType : ProgramType

/** Text. */
public data object TextType : ProgramType

/** A world entity handle; it can become stale. */
public data object EntityType : ProgramType

/** Absence of a value: the result type of functions that return nothing. */
public data object NothingType : ProgramType

/** Immutable list of [element] values. */
public data class ListType(
    /** Element type. */
    public val element: ProgramType,
) : ProgramType

/** Immutable runtime values. Immutability lets segments roll back and checkpoints copy without aliasing. */
public sealed interface ProgramValue {
    /** Static type of this value. */
    public val type: ProgramType
}

/** An [IntegerType] value. */
public data class IntegerValue(
    /** Payload. */
    public val value: Long,
) : ProgramValue {
    override val type: ProgramType get() = IntegerType
}

/** A [DecimalType] value. */
public data class DecimalValue(
    /** Payload. */
    public val value: Double,
) : ProgramValue {
    override val type: ProgramType get() = DecimalType
}

/** A [BoolType] value. */
public data class BoolValue(
    /** Payload. */
    public val value: Boolean,
) : ProgramValue {
    override val type: ProgramType get() = BoolType
}

/** A [TextType] value. */
public data class TextValue(
    /** Payload. */
    public val value: String,
) : ProgramValue {
    override val type: ProgramType get() = TextType
}

/** An [EntityType] value. */
public data class EntityValue(
    /** Referenced entity; may be stale. */
    public val entity: EntityHandle,
) : ProgramValue {
    override val type: ProgramType get() = EntityType
}

/** A [ListType] value. */
public data class ListValue(
    /** Element type, explicit so that empty lists stay typed. */
    public val element: ProgramType,
    /** Elements. */
    public val items: List<ProgramValue>,
) : ProgramValue {
    override val type: ProgramType get() = ListType(element)
}

/** The single [NothingType] value. */
public data object NothingValue : ProgramValue {
    override val type: ProgramType get() = NothingType
}

/** Binary operators. Arithmetic requires matching numeric operand types; `Add` also concatenates text. */
public enum class BinaryOp {
    /** Addition or text concatenation. */
    Add,

    /** Subtraction. */
    Subtract,

    /** Multiplication. */
    Multiply,

    /** Division; integer division truncates, and division by zero faults. */
    Divide,

    /** Integer remainder; zero divisor faults. */
    Remainder,

    /** Structural equality of same-typed values. */
    Equal,

    /** Structural inequality of same-typed values. */
    NotEqual,

    /** Numeric less-than. */
    Less,

    /** Numeric less-or-equal. */
    LessOrEqual,

    /** Numeric greater-than. */
    Greater,

    /** Numeric greater-or-equal. */
    GreaterOrEqual,

    /** Short-circuit boolean and. */
    And,

    /** Short-circuit boolean or. */
    Or,
}

/** Expressions. Every node carries its [source] for diagnostics and fault locations. */
public sealed interface Expr {
    /** Authoring origin. */
    public val source: SourceRef

    /** A constant. */
    public data class Literal(
        /** Constant value. */
        public val value: ProgramValue,
        override val source: SourceRef,
    ) : Expr

    /** A local variable or parameter. */
    public data class Variable(
        /** Variable name. */
        public val name: String,
        override val source: SourceRef,
    ) : Expr

    /** A binary operation. */
    public data class Binary(
        /** Operator. */
        public val op: BinaryOp,
        /** Left operand. */
        public val left: Expr,
        /** Right operand. */
        public val right: Expr,
        override val source: SourceRef,
    ) : Expr

    /** Boolean negation. */
    public data class Not(
        /** Boolean operand. */
        public val operand: Expr,
        override val source: SourceRef,
    ) : Expr

    /** Numeric negation. */
    public data class Negate(
        /** Numeric operand. */
        public val operand: Expr,
        override val source: SourceRef,
    ) : Expr

    /** Calls a user function declared in the same program. */
    public data class Call(
        /** Function name. */
        public val function: String,
        /** Arguments, evaluated left to right. */
        public val arguments: List<Expr>,
        override val source: SourceRef,
    ) : Expr

    /** Calls a registered host function. */
    public data class CallHost(
        /** Host function name. */
        public val function: String,
        /** Arguments, evaluated left to right. */
        public val arguments: List<Expr>,
        override val source: SourceRef,
    ) : Expr

    /** Reads a program-instance state field. */
    public data class GetState(
        /** Field name. */
        public val field: String,
        override val source: SourceRef,
    ) : Expr

    /** Reads a host-bound property of an entity; a missing component faults. */
    public data class GetProperty(
        /** Entity expression. */
        public val entity: Expr,
        /** Registered property name. */
        public val property: String,
        override val source: SourceRef,
    ) : Expr

    /** Live entities having every listed property's component, in ascending slot order. */
    public data class Query(
        /** Registered property names (at least one). */
        public val properties: List<String>,
        override val source: SourceRef,
    ) : Expr

    /** The entity running this program. */
    public data class Self(
        override val source: SourceRef,
    ) : Expr

    /** Builds a list. */
    public data class ListOf(
        /** Element type. */
        public val element: ProgramType,
        /** Element expressions. */
        public val items: List<Expr>,
        override val source: SourceRef,
    ) : Expr

    /** Returns a new list with [item] appended; charged against the allocation budget. */
    public data class Append(
        /** List expression. */
        public val list: Expr,
        /** Item expression. */
        public val item: Expr,
        override val source: SourceRef,
    ) : Expr

    /** Number of list elements. */
    public data class Size(
        /** List expression. */
        public val list: Expr,
        override val source: SourceRef,
    ) : Expr

    /** Zero-based list element; an out-of-range index faults. */
    public data class At(
        /** List expression. */
        public val list: Expr,
        /** Index expression. */
        public val index: Expr,
        override val source: SourceRef,
    ) : Expr
}

/** Statements. */
public sealed interface Stmt {
    /** Authoring origin. */
    public val source: SourceRef

    /** Declares a block-scoped local variable whose type is inferred from [value]. */
    public data class Declare(
        /** Variable name. */
        public val name: String,
        /** Initial value. */
        public val value: Expr,
        override val source: SourceRef,
    ) : Stmt

    /** Assigns a declared local variable. */
    public data class Assign(
        /** Variable name. */
        public val name: String,
        /** New value. */
        public val value: Expr,
        override val source: SourceRef,
    ) : Stmt

    /** Writes a state field; visible to later reads in this segment, committed with the segment. */
    public data class SetState(
        /** Field name. */
        public val field: String,
        /** New value. */
        public val value: Expr,
        override val source: SourceRef,
    ) : Stmt

    /** Writes a host-bound entity property; buffered until the segment commits. */
    public data class SetProperty(
        /** Entity expression. */
        public val entity: Expr,
        /** Registered property name. */
        public val property: String,
        /** New value. */
        public val value: Expr,
        override val source: SourceRef,
    ) : Stmt

    /** Conditional. */
    public data class If(
        /** Boolean condition. */
        public val condition: Expr,
        /** Statements when true. */
        public val then: List<Stmt>,
        /** Statements when false. */
        public val otherwise: List<Stmt> = emptyList(),
        override val source: SourceRef,
    ) : Stmt

    /** Loop while [condition] holds. */
    public data class While(
        /** Boolean condition. */
        public val condition: Expr,
        /** Loop body. */
        public val body: List<Stmt>,
        override val source: SourceRef,
    ) : Stmt

    /** Iterates a list snapshot, binding each element to [name]. */
    public data class ForEach(
        /** Element variable name. */
        public val name: String,
        /** List expression, evaluated once. */
        public val list: Expr,
        /** Loop body. */
        public val body: List<Stmt>,
        override val source: SourceRef,
    ) : Stmt

    /** Returns from a function (with a value unless it returns nothing) or ends a handler. */
    public data class Return(
        /** Returned value, or `null`. */
        public val value: Expr?,
        override val source: SourceRef,
    ) : Stmt

    /** Evaluates an expression for its effects and discards the result. */
    public data class Evaluate(
        /** Expression. */
        public val expr: Expr,
        override val source: SourceRef,
    ) : Stmt

    /** Emits a registered event, delivered to handlers on the next tick. */
    public data class Emit(
        /** Registered event name. */
        public val event: String,
        /** Payload, required exactly when the event declares one. */
        public val payload: Expr?,
        override val source: SourceRef,
    ) : Stmt

    /** Commits the current segment and suspends for at least one tick. */
    public data class Wait(
        /** Positive integer tick count. */
        public val ticks: Expr,
        override val source: SourceRef,
    ) : Stmt

    /**
     * Removes an object, with its children, when the segment commits. Removal happens at the end of the tick, after
     * every system has run, so other rules still see the object during this tick. A segment that faults removes
     * nothing; removing an object that no longer exists faults with [FaultKind.MissingProperty].
     */
    public data class Destroy(
        /** Object to remove. */
        public val entity: Expr,
        override val source: SourceRef,
    ) : Stmt

    /** Requests a registered external effect with a deterministic ID, committed with the segment. */
    public data class RequestEffect(
        /** Registered effect name. */
        public val effect: String,
        /** Payload. */
        public val payload: Expr,
        override val source: SourceRef,
    ) : Stmt
}

/** A typed function parameter. */
public data class Parameter(
    /** Parameter name. */
    public val name: String,
    /** Parameter type. */
    public val type: ProgramType,
)

/** A user-defined function. */
public data class FunctionDef(
    /** Unique function name within the program. */
    public val name: String,
    /** Parameters. */
    public val parameters: List<Parameter>,
    /** Return type; [NothingType] for none. */
    public val returns: ProgramType,
    /** Body. */
    public val body: List<Stmt>,
    /** Authoring origin. */
    public val source: SourceRef,
)

/** What starts a handler. */
public sealed interface Trigger {
    /** Runs once per fixed tick. */
    public data object EveryTick : Trigger

    /** Runs once per delivered event; a declared payload is bound to the local `payload`. */
    public data class OnEvent(
        /** Registered event name. */
        public val event: String,
    ) : Trigger
}

/** A When-rule: a [trigger] and the statements it runs. */
public data class Handler(
    /** Trigger. */
    public val trigger: Trigger,
    /** Body. */
    public val body: List<Stmt>,
    /** Authoring origin. */
    public val source: SourceRef,
)

/** A per-instance state field with an initial value; its type is the initial value's type. */
public data class StateField(
    /** Field name. */
    public val name: String,
    /** Initial value. */
    public val initial: ProgramValue,
    /** Authoring origin. */
    public val source: SourceRef,
)

/**
 * A portable program: the shared executable form behind visual When/If/Do rules, later graphs and agent-authored
 * behaviors. It is plain data independent of any storage format.
 */
public data class Program(
    /** Stable identifier used to re-associate checkpointed instances. */
    public val id: String,
    /** State fields. */
    public val state: List<StateField> = emptyList(),
    /** Functions. */
    public val functions: List<FunctionDef> = emptyList(),
    /** Handlers, run in declaration order. */
    public val handlers: List<Handler> = emptyList(),
)
