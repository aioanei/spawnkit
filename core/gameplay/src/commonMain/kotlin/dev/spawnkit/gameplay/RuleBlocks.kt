package dev.spawnkit.gameplay

import dev.spawnkit.program.BinaryOp
import dev.spawnkit.program.BoolValue
import dev.spawnkit.program.DecimalValue
import dev.spawnkit.program.Expr
import dev.spawnkit.program.Handler
import dev.spawnkit.program.IntegerValue
import dev.spawnkit.program.SourceRef
import dev.spawnkit.program.Stmt
import dev.spawnkit.program.TextValue
import dev.spawnkit.program.Trigger

/** Who a block acts on: this object, or the first object with a tag. */
public sealed interface Target {
    /** The object the rule belongs to. */
    public data object Self : Target

    /** The first object tagged [tag] (see `firstTagged`). */
    public data class Tagged(
        /** Tag to look for. */
        public val tag: String,
    ) : Target
}

/** A comparison between two whole numbers. */
public enum class Comparison(
    internal val op: BinaryOp,
    /** Symbol shown in blocks. */
    public val symbol: String,
) {
    /** Equal to. */
    Equal(BinaryOp.Equal, "="),

    /** Not equal to. */
    NotEqual(BinaryOp.NotEqual, "≠"),

    /** Less than. */
    Less(BinaryOp.Less, "<"),

    /** Greater than. */
    Greater(BinaryOp.Greater, ">"),

    /** Less than or equal to. */
    AtMost(BinaryOp.LessOrEqual, "≤"),

    /** Greater than or equal to. */
    AtLeast(BinaryOp.GreaterOrEqual, "≥"),
}

/** A screen direction; y points down. */
public enum class Direction(
    internal val property: String,
    internal val positive: Boolean,
    /** Friendly name. */
    public val label: String,
) {
    /** Towards smaller x. */
    Left("x", false, "left"),

    /** Towards larger x. */
    Right("x", true, "right"),

    /** Towards smaller y. */
    Up("y", false, "up"),

    /** Towards larger y. */
    Down("y", true, "down"),
}

/** A "When" or "And if" block: something that is true or false on each tick. */
public sealed interface Condition {
    /** Every tick. */
    public data object Always : Condition

    /** The first tick of play. */
    public data object GameStarts : Condition

    /** Once every [seconds] seconds, at the end of each period. */
    public data class EverySeconds(
        /** Period, from 1 second to one day. */
        public val seconds: Long,
    ) : Condition {
        init {
            require(seconds in 1..MAX_SECONDS) { "Choose from 1 to $MAX_SECONDS seconds" }
        }
    }

    /** This object overlaps an object tagged [tag]. */
    public data class Touching(
        /** Tag of the other object. */
        public val tag: String,
    ) : Condition

    /** A finger or click went down on this object. */
    public data object Tapped : Condition

    /** Input action [action] is held. */
    public data class KeyHeld(
        /** Action ID, for example [StandardActions.RIGHT]. */
        public val action: String,
    ) : Condition

    /** Input action [action] went down this tick. */
    public data class KeyPressed(
        /** Action ID. */
        public val action: String,
    ) : Condition

    /** The [target]'s number compares to [value]. */
    public data class NumberIs(
        /** Object holding the number. */
        public val target: Target,
        /** How to compare. */
        public val comparison: Comparison,
        /** Value to compare with. */
        public val value: Long,
    ) : Condition

    /** The number of objects tagged [tag] compares to [value]. */
    public data class CountIs(
        /** Tag to count. */
        public val tag: String,
        /** How to compare. */
        public val comparison: Comparison,
        /** Value to compare with. */
        public val value: Long,
    ) : Condition
}

/** A "Do" block. */
public sealed interface Action {
    /** Moves this object in [direction] at [speed] world units per second. */
    public data class Move(
        /** Direction. */
        public val direction: Direction,
        /** Units per second, from 0 to 1000. */
        public val speed: Double,
    ) : Action {
        init {
            require(speed.isFinite() && speed >= 0.0 && speed <= MAX_SPEED) { "Choose a speed from 0 to $MAX_SPEED" }
        }
    }

    /** Adds [by] (which may be negative) to the [target]'s number. */
    public data class ChangeNumber(
        /** Object holding the number. */
        public val target: Target,
        /** Amount to add. */
        public val by: Long,
    ) : Action

    /** Sets the [target]'s number to [to]. */
    public data class SetNumber(
        /** Object holding the number. */
        public val target: Target,
        /** New value. */
        public val to: Long,
    ) : Action

    /** Sets the [target]'s text to [prefix] followed by its number, for labels such as "Score: 3". */
    public data class ShowNumber(
        /** Object with both a number and a text. */
        public val target: Target,
        /** Text before the number. */
        public val prefix: String,
    ) : Action

    /** Removes this object (and its children) at the end of the tick. */
    public data object RemoveSelf : Action
}

/** A visual rule: When [condition], and if every one of [alsoIf], do [actions] in order. Checked every tick. */
public data class Rule(
    /** The "When" block. */
    public val condition: Condition,
    /** "And if" blocks; all must be true. */
    public val alsoIf: List<Condition> = emptyList(),
    /** "Do" blocks, run in order. */
    public val actions: List<Action> = emptyList(),
)

/** One of an object's rules as the editor shows it. */
public sealed interface RuleItem {
    /** A rule made of blocks. */
    public data class Blocks(
        /** The rule. */
        public val rule: Rule,
    ) : RuleItem

    /** A rule written by another tool or an agent that is not made of blocks; kept exactly as it is. */
    public data class Advanced(
        /** The handler, unchanged. */
        public val handler: Handler,
    ) : RuleItem
}

/** Which part of a rule a block is in. */
public enum class BlockPart {
    /** The "When" block (index 0) or an "And if" block (index 1 and up). */
    Condition,

    /** A "Do" block. */
    Action,
}

/** Where a block sits: rule number, part and position within the part. */
public data class BlockLocation(
    /** Rule index on the object. */
    public val rule: Int,
    /** Condition or action. */
    public val part: BlockPart,
    /** Position: for conditions 0 is "When" and 1.. are "And if"; for actions the "Do" position. */
    public val index: Int,
)

/**
 * The friendly block vocabulary of the standard engine, compiled to and from `core/program` handlers so visual
 * rules, advanced tools and agents share one executable model.
 *
 * A rule compiles to an every-tick handler containing one `if`. Every node of a block carries the block's node ID
 * (`rule<i>.if<k>` or `rule<i>.do<k>`) and its description, so diagnostics and faults point at the block. Decompiling
 * recognises a handler as blocks only when compiling the recognised rule reproduces it exactly; anything else is an
 * [RuleItem.Advanced] rule and is kept unchanged.
 */
public object RuleBlocks {
    /** Compiles [rules], in order. */
    public fun compile(rules: List<Rule>): List<Handler> = rules.mapIndexed { index, rule -> compile(rule, index) }

    /** Compiles [items], keeping advanced rules unchanged. */
    public fun compileItems(items: List<RuleItem>): List<Handler> =
        items.mapIndexed { index, item ->
            when (item) {
                is RuleItem.Blocks -> compile(item.rule, index)
                is RuleItem.Advanced -> item.handler
            }
        }

    /**
     * The editor's view of [handlers]: blocks where a handler is exactly a compiled rule, advanced otherwise. The rule
     * number is read from the handler's own node ID, so rules stay blocks when others are added or removed around
     * them; [compileItems] renumbers by position.
     */
    public fun decompile(handlers: List<Handler>): List<RuleItem> =
        handlers.map { handler ->
            val index =
                RULE_ID
                    .matchEntire(handler.source.nodeId)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
            val rule = index?.let { i -> recognise(handler)?.takeIf { compile(it, i) == handler } }
            rule?.let { RuleItem.Blocks(it) } ?: RuleItem.Advanced(handler)
        }

    /** The block a program node ID belongs to, or `null` for nodes outside blocks. */
    public fun locate(nodeId: String): BlockLocation? {
        val match = BLOCK_ID.matchEntire(nodeId) ?: return null
        val (rule, part, index) = match.destructured
        return BlockLocation(rule.toInt(), if (part == "if") BlockPart.Condition else BlockPart.Action, index.toInt())
    }

    /** Plain-language description of a condition block. */
    public fun describe(condition: Condition): String =
        when (condition) {
            Condition.Always -> "Every moment"
            Condition.GameStarts -> "Game starts"
            is Condition.EverySeconds -> if (condition.seconds == 1L) "Every second" else "Every ${condition.seconds} seconds"
            is Condition.Touching -> "Touching ${condition.tag}"
            Condition.Tapped -> "Tapped"
            is Condition.KeyHeld -> "Holding ${actionName(condition.action)}"
            is Condition.KeyPressed -> "Pressed ${actionName(condition.action)}"
            is Condition.NumberIs -> "${possessive(condition.target)} number ${condition.comparison.symbol} ${condition.value}"
            is Condition.CountIs -> "Count of ${condition.tag} ${condition.comparison.symbol} ${condition.value}"
        }

    /** Plain-language description of an action block. */
    public fun describe(action: Action): String =
        when (action) {
            is Action.Move -> {
                "Move ${action.direction.label} at ${number(action.speed)} per second"
            }

            is Action.ChangeNumber -> {
                if (action.by >= 0) "Add ${action.by} to ${name(action.target)}" else "Take ${-action.by} from ${name(action.target)}"
            }

            is Action.SetNumber -> {
                "Set ${possessive(action.target)} number to ${action.to}"
            }

            is Action.ShowNumber -> {
                "Show ${possessive(action.target)} number as \"${action.prefix}n\""
            }

            Action.RemoveSelf -> {
                "Remove this object"
            }
        }

    private fun compile(
        rule: Rule,
        index: Int,
    ): Handler {
        val conditions = listOf(rule.condition) + rule.alsoIf
        val combined =
            conditions
                .mapIndexed { k, condition -> condition(condition, SourceRef("rule$index.if$k", describe(condition))) }
                .reduceIndexed { k, left, right -> Expr.Binary(BinaryOp.And, left, right, SourceRef("rule$index.and$k", "and")) }
        val actions = rule.actions.mapIndexed { k, action -> action(action, SourceRef("rule$index.do$k", describe(action))) }
        val body = Stmt.If(combined, actions, emptyList(), SourceRef("rule$index.when", "When"))
        return Handler(Trigger.EveryTick, listOf(body), SourceRef("rule$index", "When ${describe(rule.condition).lowercase()}"))
    }

    private fun condition(
        condition: Condition,
        s: SourceRef,
    ): Expr =
        when (condition) {
            Condition.Always -> {
                Expr.Literal(BoolValue(true), s)
            }

            // The world counts ticks from 1: the first tick rules see is tick 1.
            Condition.GameStarts -> {
                Expr.Binary(BinaryOp.Equal, tick(s), int(1, s), s)
            }

            is Condition.EverySeconds -> {
                val period = condition.seconds * TICKS_PER_SECOND
                Expr.Binary(BinaryOp.Equal, Expr.Binary(BinaryOp.Remainder, tick(s), int(period, s), s), int(0, s), s)
            }

            is Condition.Touching -> {
                call("touching", s, text(condition.tag, s))
            }

            Condition.Tapped -> {
                call("tapped", s)
            }

            is Condition.KeyHeld -> {
                call("isDown", s, text(condition.action, s))
            }

            is Condition.KeyPressed -> {
                call("wasPressed", s, text(condition.action, s))
            }

            is Condition.NumberIs -> {
                Expr.Binary(condition.comparison.op, number(condition.target, s), int(condition.value, s), s)
            }

            is Condition.CountIs -> {
                Expr.Binary(condition.comparison.op, call("countTagged", s, text(condition.tag, s)), int(condition.value, s), s)
            }
        }

    private fun action(
        action: Action,
        s: SourceRef,
    ): Stmt =
        when (action) {
            is Action.Move -> {
                val step =
                    Expr.Binary(
                        BinaryOp.Divide,
                        Expr.Literal(DecimalValue(action.speed), s),
                        Expr.Literal(DecimalValue(TICKS_PER_SECOND.toDouble()), s),
                        s,
                    )
                val current = Expr.GetProperty(Expr.Self(s), action.direction.property, s)
                val op = if (action.direction.positive) BinaryOp.Add else BinaryOp.Subtract
                Stmt.SetProperty(Expr.Self(s), action.direction.property, Expr.Binary(op, current, step, s), s)
            }

            is Action.ChangeNumber -> {
                Stmt.SetProperty(
                    target(action.target, s),
                    NUMBER,
                    Expr.Binary(BinaryOp.Add, number(action.target, s), int(action.by, s), s),
                    s,
                )
            }

            is Action.SetNumber -> {
                Stmt.SetProperty(target(action.target, s), NUMBER, int(action.to, s), s)
            }

            is Action.ShowNumber -> {
                Stmt.SetProperty(
                    target(action.target, s),
                    TEXT,
                    Expr.Binary(BinaryOp.Add, text(action.prefix, s), call("textOf", s, number(action.target, s)), s),
                    s,
                )
            }

            Action.RemoveSelf -> {
                Stmt.Destroy(Expr.Self(s), s)
            }
        }

    /** Extracts a candidate rule from a handler's shape; [decompile] then verifies it compiles back exactly. */
    private fun recognise(handler: Handler): Rule? {
        if (handler.trigger != Trigger.EveryTick) return null
        val body = handler.body.singleOrNull() as? Stmt.If ?: return null
        if (body.otherwise.isNotEmpty()) return null
        val conditions = flatten(body.condition).map { recogniseCondition(it) ?: return null }
        val actions = body.then.map { recogniseAction(it) ?: return null }
        return Rule(conditions.first(), conditions.drop(1), actions)
    }

    private fun flatten(expr: Expr): List<Expr> =
        if (expr is Expr.Binary && expr.op == BinaryOp.And) flatten(expr.left) + expr.right else listOf(expr)

    private fun recogniseCondition(expr: Expr): Condition? =
        runCatching {
            when {
                expr is Expr.Literal && expr.value == BoolValue(true) -> Condition.Always
                expr.isCall("tapped") -> Condition.Tapped
                expr.isCall("touching") -> Condition.Touching(expr.textArgument())
                expr.isCall("isDown") -> Condition.KeyHeld(expr.textArgument())
                expr.isCall("wasPressed") -> Condition.KeyPressed(expr.textArgument())
                expr is Expr.Binary -> recogniseComparison(expr)
                else -> null
            }
        }.getOrNull()

    private fun recogniseComparison(expr: Expr.Binary): Condition? {
        val left = expr.left
        val right = (expr.right as? Expr.Literal)?.value as? IntegerValue ?: return null
        if (expr.op == BinaryOp.Equal && left.isCall("tick")) return Condition.GameStarts
        if (expr.op == BinaryOp.Equal && left is Expr.Binary && left.op == BinaryOp.Remainder) {
            val period = (left.right as? Expr.Literal)?.value as? IntegerValue ?: return null
            return Condition.EverySeconds(period.value / TICKS_PER_SECOND)
        }
        val comparison = Comparison.entries.firstOrNull { it.op == expr.op } ?: return null
        return when {
            left is Expr.GetProperty && left.property == NUMBER -> {
                Condition.NumberIs(
                    recogniseTarget(left.entity) ?: return null,
                    comparison,
                    right.value,
                )
            }

            left.isCall("countTagged") -> {
                Condition.CountIs(left.textArgument(), comparison, right.value)
            }

            else -> {
                null
            }
        }
    }

    private fun recogniseAction(stmt: Stmt): Action? =
        runCatching {
            when {
                stmt is Stmt.Destroy -> {
                    Action.RemoveSelf
                }

                stmt is Stmt.SetProperty && stmt.property == NUMBER -> {
                    val target = recogniseTarget(stmt.entity) ?: return@runCatching null
                    val value = stmt.value
                    when {
                        value is Expr.Literal -> Action.SetNumber(target, (value.value as IntegerValue).value)
                        value is Expr.Binary -> Action.ChangeNumber(target, ((value.right as Expr.Literal).value as IntegerValue).value)
                        else -> null
                    }
                }

                stmt is Stmt.SetProperty && stmt.property == TEXT -> {
                    val value = stmt.value as Expr.Binary
                    Action.ShowNumber(
                        recogniseTarget(stmt.entity) ?: return@runCatching null,
                        ((value.left as Expr.Literal).value as TextValue).value,
                    )
                }

                stmt is Stmt.SetProperty && (stmt.property == "x" || stmt.property == "y") -> {
                    val value = stmt.value as Expr.Binary
                    val positive = value.op == BinaryOp.Add
                    val direction = Direction.entries.first { it.property == stmt.property && it.positive == positive }
                    val speed = (((value.right as Expr.Binary).left as Expr.Literal).value as DecimalValue).value
                    Action.Move(direction, speed)
                }

                else -> {
                    null
                }
            }
        }.getOrNull()

    private fun recogniseTarget(expr: Expr): Target? =
        when {
            expr is Expr.Self -> Target.Self
            expr.isCall("firstTagged") -> Target.Tagged(expr.textArgument())
            else -> null
        }

    private fun Expr.isCall(name: String): Boolean = this is Expr.CallHost && function == name

    private fun Expr.textArgument(): String = (((this as Expr.CallHost).arguments.single() as Expr.Literal).value as TextValue).value

    private fun target(
        target: Target,
        s: SourceRef,
    ): Expr =
        when (target) {
            Target.Self -> Expr.Self(s)
            is Target.Tagged -> call("firstTagged", s, text(target.tag, s))
        }

    private fun number(
        target: Target,
        s: SourceRef,
    ): Expr = Expr.GetProperty(target(target, s), NUMBER, s)

    private fun tick(s: SourceRef) = call("tick", s)

    private fun call(
        name: String,
        s: SourceRef,
        vararg arguments: Expr,
    ) = Expr.CallHost(name, arguments.toList(), s)

    private fun int(
        value: Long,
        s: SourceRef,
    ) = Expr.Literal(IntegerValue(value), s)

    private fun text(
        value: String,
        s: SourceRef,
    ) = Expr.Literal(TextValue(value), s)

    private fun actionName(id: String): String = StandardActions.ALL.firstOrNull { it.first == id }?.second ?: id

    private fun name(target: Target): String =
        when (target) {
            Target.Self -> "this object"
            is Target.Tagged -> target.tag
        }

    private fun possessive(target: Target): String =
        when (target) {
            Target.Self -> "This object's"
            is Target.Tagged -> "${target.tag}'s"
        }

    private fun number(value: Double): String =
        if (value == kotlin.math.floor(value) &&
            value < 1e9
        ) {
            value.toLong().toString()
        } else {
            value.toString()
        }

    private val RULE_ID = Regex("rule(\\d+)")
    private val BLOCK_ID = Regex("rule(\\d+)\\.(if|do)(\\d+)")
    private const val NUMBER = "number"
    private const val TEXT = "text_text"
}

private const val TICKS_PER_SECOND = 60L
private const val MAX_SECONDS = 86_400L
private const val MAX_SPEED = 1_000.0
