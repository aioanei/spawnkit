package dev.spawnkit.editing

import dev.spawnkit.program.BinaryOp
import dev.spawnkit.program.BoolType
import dev.spawnkit.program.BoolValue
import dev.spawnkit.program.DecimalType
import dev.spawnkit.program.DecimalValue
import dev.spawnkit.program.EntityType
import dev.spawnkit.program.EntityValue
import dev.spawnkit.program.Expr
import dev.spawnkit.program.Handler
import dev.spawnkit.program.IntegerType
import dev.spawnkit.program.IntegerValue
import dev.spawnkit.program.ListType
import dev.spawnkit.program.ListValue
import dev.spawnkit.program.NothingType
import dev.spawnkit.program.NothingValue
import dev.spawnkit.program.ProgramType
import dev.spawnkit.program.ProgramValue
import dev.spawnkit.program.SourceRef
import dev.spawnkit.program.Stmt
import dev.spawnkit.program.TextType
import dev.spawnkit.program.TextValue
import dev.spawnkit.program.Trigger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Storage adapter for every portable handler node; executable host names remain open strings. */
internal object RuleJson {
    fun encode(handler: Handler): JsonElement =
        jsonObject(
            "source" to source(handler.source),
            "trigger" to
                when (val trigger = handler.trigger) {
                    Trigger.EveryTick -> jsonObject("kind" to JsonPrimitive("everyTick"))
                    is Trigger.OnEvent -> jsonObject("kind" to JsonPrimitive("onEvent"), "event" to JsonPrimitive(trigger.event))
                },
            "body" to statements(handler.body),
        )

    fun decode(element: JsonElement): Handler =
        readObject(element) {
            Handler(
                readObject(get("trigger")) {
                    when (val kind = string("kind")) {
                        "everyTick" -> Trigger.EveryTick
                        "onEvent" -> Trigger.OnEvent(string("event"))
                        else -> throw IllegalArgumentException("Unknown rule trigger '$kind'")
                    }
                },
                array("body").map(::statement),
                source(get("source")),
            )
        }

    private fun source(value: SourceRef): JsonElement =
        jsonObject(
            "nodeId" to JsonPrimitive(value.nodeId),
            "label" to JsonPrimitive(value.label),
        )

    private fun source(value: JsonElement): SourceRef = readObject(value) { SourceRef(string("nodeId"), string("label")) }

    private fun statements(body: List<Stmt>) = JsonArray(body.map(::statement))

    private fun expressions(items: List<Expr>) = JsonArray(items.map(::expression))

    private fun text(value: String) = JsonPrimitive(value)

    private fun node(
        kind: String,
        source: SourceRef,
        vararg fields: Pair<String, JsonElement>,
    ): JsonElement = jsonObject("kind" to text(kind), "source" to source(source), *fields)

    private fun expression(expr: Expr): JsonElement =
        when (expr) {
            is Expr.Literal -> {
                node("literal", expr.source, "value" to value(expr.value))
            }

            is Expr.Variable -> {
                node("variable", expr.source, "name" to text(expr.name))
            }

            is Expr.Binary -> {
                node(
                    "binary",
                    expr.source,
                    "op" to text(expr.op.name),
                    "left" to expression(expr.left),
                    "right" to expression(expr.right),
                )
            }

            is Expr.Not -> {
                node("not", expr.source, "operand" to expression(expr.operand))
            }

            is Expr.Negate -> {
                node("negate", expr.source, "operand" to expression(expr.operand))
            }

            is Expr.Call -> {
                node("call", expr.source, "function" to text(expr.function), "arguments" to expressions(expr.arguments))
            }

            is Expr.CallHost -> {
                node("host", expr.source, "function" to text(expr.function), "arguments" to expressions(expr.arguments))
            }

            is Expr.GetState -> {
                node("getState", expr.source, "field" to text(expr.field))
            }

            is Expr.GetProperty -> {
                node("getProperty", expr.source, "entity" to expression(expr.entity), "property" to text(expr.property))
            }

            is Expr.Query -> {
                node("query", expr.source, "properties" to JsonArray(expr.properties.map(::text)))
            }

            is Expr.Self -> {
                node("self", expr.source)
            }

            is Expr.ListOf -> {
                node("list", expr.source, "element" to type(expr.element), "items" to expressions(expr.items))
            }

            is Expr.Append -> {
                node("append", expr.source, "list" to expression(expr.list), "item" to expression(expr.item))
            }

            is Expr.Size -> {
                node("size", expr.source, "list" to expression(expr.list))
            }

            is Expr.At -> {
                node("at", expr.source, "list" to expression(expr.list), "index" to expression(expr.index))
            }
        }

    private fun expression(element: JsonElement): Expr =
        readObject(element) {
            val s = source(get("source"))
            when (val kind = string("kind")) {
                "literal" -> {
                    Expr.Literal(value(get("value")), s)
                }

                "variable" -> {
                    Expr.Variable(string("name"), s)
                }

                "binary" -> {
                    Expr.Binary(BinaryOp.valueOf(string("op")), expression(get("left")), expression(get("right")), s)
                }

                "not" -> {
                    Expr.Not(expression(get("operand")), s)
                }

                "negate" -> {
                    Expr.Negate(expression(get("operand")), s)
                }

                "call" -> {
                    Expr.Call(string("function"), array("arguments").map(::expression), s)
                }

                "host" -> {
                    Expr.CallHost(string("function"), array("arguments").map(::expression), s)
                }

                "getState" -> {
                    Expr.GetState(string("field"), s)
                }

                "getProperty" -> {
                    Expr.GetProperty(expression(get("entity")), string("property"), s)
                }

                "query" -> {
                    Expr.Query(
                        array("properties").map {
                            requireNotNull(
                                (it as? JsonPrimitive)
                                    ?.takeIf { p ->
                                        p.isString
                                    }?.content,
                            )
                        },
                        s,
                    )
                }

                "self" -> {
                    Expr.Self(s)
                }

                "list" -> {
                    Expr.ListOf(type(get("element")), array("items").map(::expression), s)
                }

                "append" -> {
                    Expr.Append(expression(get("list")), expression(get("item")), s)
                }

                "size" -> {
                    Expr.Size(expression(get("list")), s)
                }

                "at" -> {
                    Expr.At(expression(get("list")), expression(get("index")), s)
                }

                else -> {
                    throw IllegalArgumentException("Unknown rule expression '$kind'")
                }
            }
        }

    private fun statement(stmt: Stmt): JsonElement =
        when (stmt) {
            is Stmt.Declare -> {
                node("declare", stmt.source, "name" to text(stmt.name), "value" to expression(stmt.value))
            }

            is Stmt.Assign -> {
                node("assign", stmt.source, "name" to text(stmt.name), "value" to expression(stmt.value))
            }

            is Stmt.SetState -> {
                node("setState", stmt.source, "field" to text(stmt.field), "value" to expression(stmt.value))
            }

            is Stmt.SetProperty -> {
                node(
                    "setProperty",
                    stmt.source,
                    "entity" to expression(stmt.entity),
                    "property" to text(stmt.property),
                    "value" to expression(stmt.value),
                )
            }

            is Stmt.If -> {
                node(
                    "if",
                    stmt.source,
                    "condition" to expression(stmt.condition),
                    "then" to statements(stmt.then),
                    "otherwise" to statements(stmt.otherwise),
                )
            }

            is Stmt.While -> {
                node("while", stmt.source, "condition" to expression(stmt.condition), "body" to statements(stmt.body))
            }

            is Stmt.ForEach -> {
                node(
                    "forEach",
                    stmt.source,
                    "name" to text(stmt.name),
                    "list" to expression(stmt.list),
                    "body" to statements(stmt.body),
                )
            }

            is Stmt.Return -> {
                node("return", stmt.source, "value" to (stmt.value?.let(::expression) ?: JsonNull))
            }

            is Stmt.Evaluate -> {
                node("evaluate", stmt.source, "expression" to expression(stmt.expr))
            }

            is Stmt.Emit -> {
                node("emit", stmt.source, "event" to text(stmt.event), "payload" to (stmt.payload?.let(::expression) ?: JsonNull))
            }

            is Stmt.Wait -> {
                node("wait", stmt.source, "ticks" to expression(stmt.ticks))
            }

            is Stmt.Destroy -> {
                node("destroy", stmt.source, "entity" to expression(stmt.entity))
            }

            is Stmt.RequestEffect -> {
                node("effect", stmt.source, "effect" to text(stmt.effect), "payload" to expression(stmt.payload))
            }
        }

    private fun statement(element: JsonElement): Stmt =
        readObject(element) {
            val s = source(get("source"))

            fun nullable(name: String): Expr? = get(name).let { if (it == JsonNull) null else expression(it) }
            when (val kind = string("kind")) {
                "declare" -> Stmt.Declare(string("name"), expression(get("value")), s)
                "assign" -> Stmt.Assign(string("name"), expression(get("value")), s)
                "setState" -> Stmt.SetState(string("field"), expression(get("value")), s)
                "setProperty" -> Stmt.SetProperty(expression(get("entity")), string("property"), expression(get("value")), s)
                "if" -> Stmt.If(expression(get("condition")), array("then").map(::statement), array("otherwise").map(::statement), s)
                "while" -> Stmt.While(expression(get("condition")), array("body").map(::statement), s)
                "forEach" -> Stmt.ForEach(string("name"), expression(get("list")), array("body").map(::statement), s)
                "return" -> Stmt.Return(nullable("value"), s)
                "evaluate" -> Stmt.Evaluate(expression(get("expression")), s)
                "emit" -> Stmt.Emit(string("event"), nullable("payload"), s)
                "wait" -> Stmt.Wait(expression(get("ticks")), s)
                "destroy" -> Stmt.Destroy(expression(get("entity")), s)
                "effect" -> Stmt.RequestEffect(string("effect"), expression(get("payload")), s)
                else -> throw IllegalArgumentException("Unknown rule instruction '$kind'")
            }
        }

    private fun type(value: ProgramType): JsonElement =
        when (value) {
            IntegerType -> text("integer")
            DecimalType -> text("decimal")
            BoolType -> text("boolean")
            TextType -> text("text")
            EntityType -> text("entity")
            NothingType -> text("nothing")
            is ListType -> jsonObject("list" to type(value.element))
        }

    private fun type(value: JsonElement): ProgramType =
        if (value is JsonPrimitive && value.isString) {
            when (value.content) {
                "integer" -> IntegerType
                "decimal" -> DecimalType
                "boolean" -> BoolType
                "text" -> TextType
                "entity" -> EntityType
                "nothing" -> NothingType
                else -> throw IllegalArgumentException("Unknown rule value type")
            }
        } else {
            readObject(value) { ListType(type(get("list"))) }
        }

    private fun value(value: ProgramValue): JsonElement =
        when (value) {
            is IntegerValue -> {
                jsonObject("kind" to text("integer"), "value" to JsonPrimitive(value.value))
            }

            is DecimalValue -> {
                require(value.value.isFinite()) { "A rule contains a number that cannot be saved" }
                jsonObject("kind" to text("decimal"), "value" to decimalJson(value.value))
            }

            is BoolValue -> {
                jsonObject("kind" to text("boolean"), "value" to JsonPrimitive(value.value))
            }

            is TextValue -> {
                jsonObject("kind" to text("text"), "value" to text(value.value))
            }

            NothingValue -> {
                jsonObject("kind" to text("nothing"))
            }

            is ListValue -> {
                jsonObject("kind" to text("list"), "element" to type(value.element), "items" to JsonArray(value.items.map(::value)))
            }

            is EntityValue -> {
                throw IllegalArgumentException("A rule contains a runtime-only object handle. Use this object, a tag or a query instead")
            }
        }

    private fun value(element: JsonElement): ProgramValue =
        readObject(element) {
            when (val kind = string("kind")) {
                "integer" -> {
                    IntegerValue(integer("value"))
                }

                "decimal" -> {
                    DecimalValue(
                        requireNotNull(
                            (get("value") as? JsonPrimitive)
                                ?.takeUnless {
                                    it.isString
                                }?.doubleOrNull
                                ?.takeIf { it.isFinite() },
                        ),
                    )
                }

                "boolean" -> {
                    BoolValue(requireNotNull((get("value") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull))
                }

                "text" -> {
                    TextValue(string("value"))
                }

                "nothing" -> {
                    NothingValue
                }

                "list" -> {
                    ListValue(type(get("element")), array("items").map(::value))
                }

                else -> {
                    throw IllegalArgumentException("Unknown rule literal '$kind'")
                }
            }
        }
}
