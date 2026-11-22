package dev.spawnkit.ai

import dev.spawnkit.editing.OperationResult
import dev.spawnkit.editing.Parameter
import dev.spawnkit.editing.Picked
import dev.spawnkit.editing.StagedChanges
import dev.spawnkit.editing.ToolDefinition
import dev.spawnkit.editing.buildFromJson
import dev.spawnkit.editing.capabilityProblems
import dev.spawnkit.editing.encodeArguments
import dev.spawnkit.editing.toolDefinitions
import dev.spawnkit.gameplay.RuleBlockCatalog
import dev.spawnkit.gameplay.RuleBlocks
import dev.spawnkit.gameplay.RuleItem
import dev.spawnkit.gameplay.StandardEngine
import dev.spawnkit.runtime.ValueKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/** What a tool call did, so a session can react without parsing the text given to the model. */
public sealed interface ToolOutcome {
    /** Text returned to the model (JSON for structured results). */
    public val text: String

    /** A change was staged; [label] is its review label. */
    public data class Staged(
        override val text: String,
        /** Review label of the staged step. */
        public val label: String,
    ) : ToolOutcome

    /** Information was returned; nothing changed. */
    public data class Looked(
        override val text: String,
    ) : ToolOutcome

    /** The call was refused; nothing changed. */
    public data class Refused(
        override val text: String,
    ) : ToolOutcome

    /** The agent asks the creator a clarifying question and waits for the answer. */
    public data class Question(
        override val text: String,
        /** Question for the creator. */
        public val question: String,
        /** Suggested answers, possibly empty. */
        public val options: List<String>,
    ) : ToolOutcome

    /** The agent finished and summarized its proposal. */
    public data class Finished(
        override val text: String,
        /** Summary in the creator's words. */
        public val summary: String,
    ) : ToolOutcome
}

/**
 * The tools an agent can use on one staged proposal (ADR-0009). Editing tools are derived from the engine's operation
 * catalog, so they are exactly the builder's capabilities, and they only ever change [staged]. Reading tools inspect
 * the staged game, validate it in the real engine and trace rules by headless play. Agents never receive credentials:
 * no tool takes or returns keys, and outward-facing actions such as publishing are not tools.
 */
public class AgentToolbox(
    private val engine: StandardEngine,
    private val staged: StagedChanges,
    private val sceneId: String,
) {
    private val editing: List<ToolDefinition> = engine.operations.toolDefinitions(engine.runtime)

    /** Every tool, editing tools first. */
    public val tools: List<ToolDefinition> = editing + readingTools()

    /** Runs tool [name] with JSON [input]. Unknown tools and bad inputs are refused with an explanation. */
    public fun call(
        name: String,
        input: JsonObject,
    ): ToolOutcome =
        when (name) {
            LOOK -> ToolOutcome.Looked(look().toString())
            COMPONENTS -> ToolOutcome.Looked(components().toString())
            CHECK -> ToolOutcome.Looked(check())
            TRACE -> trace(input)
            ASK -> ask(input)
            FINISH -> finish(input)
            else -> edit(name, input)
        }

    private fun edit(
        name: String,
        input: JsonObject,
    ): ToolOutcome {
        if (engine.operations[name] == null) return ToolOutcome.Refused(problem("There is no tool called '$name'"))
        val built = engine.operations.buildFromJson(name, input, staged.catalogContext())
        return when (val result = staged.apply(built)) {
            is OperationResult.Rejected -> {
                ToolOutcome.Refused(problem(result.message))
            }

            is OperationResult.Applied -> {
                val label = staged.labels.lastOrNull() ?: "No change"
                val text =
                    JsonObject(
                        mapOf(
                            "ok" to JsonPrimitive(true),
                            "step" to JsonPrimitive(label),
                            "created" to JsonArray(result.createdIds.map(::JsonPrimitive)),
                        ),
                    )
                ToolOutcome.Staged(text.toString(), label)
            }
        }
    }

    /** The staged game as the creator sees it: objects with their values and rules as blocks. */
    private fun look(): JsonObject {
        val project = staged.project
        val scene = checkNotNull(project.scene(sceneId))
        return JsonObject(
            mapOf(
                "game" to JsonPrimitive(project.name),
                "scene" to JsonPrimitive(scene.id),
                "sceneName" to JsonPrimitive(scene.name),
                "objects" to
                    JsonArray(
                        scene.objects.map { obj ->
                            JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive(obj.id),
                                    "name" to JsonPrimitive(obj.name),
                                    "parent" to (obj.parent?.let(::JsonPrimitive) ?: JsonNull),
                                    "components" to
                                        JsonObject(
                                            obj.components.associate { doc ->
                                                doc.componentId to
                                                    JsonObject(doc.values.mapValues { (_, value) -> plain(value) })
                                            },
                                        ),
                                    "rules" to JsonArray(RuleBlocks.decompile(obj.rules).mapIndexed { i, item -> rule(i, item) }),
                                ),
                            )
                        },
                    ),
            ),
        )
    }

    private fun rule(
        index: Int,
        item: RuleItem,
    ): JsonElement =
        when (item) {
            is RuleItem.Advanced -> {
                JsonObject(
                    mapOf(
                        "rule" to JsonPrimitive(index + 1),
                        "advanced" to JsonPrimitive(item.handler.source.label),
                        "note" to
                            JsonPrimitive(
                                "Not made of blocks; it can be moved, duplicated or deleted but not edited block by block.",
                            ),
                    ),
                )
            }

            is RuleItem.Blocks -> {
                val rule = item.rule
                val block = listOf(Parameter("block", "Block", "Block", RuleBlockCatalog.blockType))

                fun json(picked: Picked) = encodeArguments(block, mapOf("block" to picked)).getValue("block")
                JsonObject(
                    mapOf(
                        "rule" to JsonPrimitive(index + 1),
                        "reads" to JsonPrimitive(RuleBlocks.describe(rule.condition)),
                        "when" to json(RuleBlockCatalog.picked(rule.condition)),
                        "and_if" to JsonArray(rule.alsoIf.map { json(RuleBlockCatalog.picked(it)) }),
                        "do" to JsonArray(rule.actions.map { json(RuleBlockCatalog.picked(it)) }),
                    ),
                )
            }
        }

    /** Installed components and their properties, so agents choose valid IDs and values. */
    private fun components(): JsonArray =
        JsonArray(
            engine.runtime.catalog.map { descriptor ->
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(descriptor.component.id),
                        "name" to JsonPrimitive(descriptor.component.authoring.name),
                        "help" to JsonPrimitive(descriptor.component.authoring.description),
                        "properties" to
                            JsonArray(
                                descriptor.properties.map { property ->
                                    JsonObject(
                                        mapOf(
                                            "id" to JsonPrimitive(property.id),
                                            "name" to JsonPrimitive(property.authoring.name),
                                            "kind" to JsonPrimitive(kind(property.kind)),
                                            "default" to plain(property.default),
                                        ),
                                    )
                                },
                            ),
                    ),
                )
            },
        )

    /** Problems the real engine finds in the staged game, as Play would report them. */
    private fun check(): String {
        val project = staged.project
        val capabilities = project.capabilityProblems(engine.runtime)
        if (capabilities.isNotEmpty()) return "The game cannot run: ${capabilities.joinToString("; ")}"
        val world = staged.buildWorld(sceneId)
        val scene = checkNotNull(project.scene(sceneId))
        if (world.diagnostics.isEmpty()) return "No problems: every object's rules are ready to run."
        return world.diagnostics.entries.joinToString("\n") { (id, problems) ->
            "${scene.find(id)?.name ?: id}: " + problems.joinToString("; ") { it.message }
        }
    }

    private fun trace(input: JsonObject): ToolOutcome {
        val seconds = (input["seconds"] as? JsonPrimitive)?.doubleOrNull ?: 3.0
        val held =
            (input["hold"] as? JsonArray).orEmpty().map { entry ->
                val item = entry as? JsonObject ?: return ToolOutcome.Refused(problem("Each held input needs action, from and to"))
                HeldInput(
                    item["action"]?.jsonPrimitive?.content ?: return ToolOutcome.Refused(problem("Name the input action to hold")),
                    item["from"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    item["to"]?.jsonPrimitive?.doubleOrNull ?: seconds,
                )
            }
        val unknown = held.firstOrNull { it.action !in engine.actions }
        if (unknown != null) return ToolOutcome.Refused(problem("There is no input action '${unknown.action}'"))
        val objects = (input["objects"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
        if (seconds <= 0.0 ||
            seconds > MAX_TRACE_SECONDS
        ) {
            return ToolOutcome.Refused(problem("Play from 0.1 to $MAX_TRACE_SECONDS seconds"))
        }
        return ToolOutcome.Looked(RuleTrace(engine).run(staged.project, sceneId, seconds, held, objects).explain())
    }

    private fun ask(input: JsonObject): ToolOutcome {
        val question =
            input["question"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return ToolOutcome.Refused(problem("Ask a question"))
        val options = (input["options"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
        return ToolOutcome.Question("Waiting for the creator's answer.", question, options)
    }

    private fun finish(input: JsonObject): ToolOutcome {
        val summary =
            input["summary"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return ToolOutcome.Refused(problem("Give a summary"))
        return ToolOutcome.Finished("Done.", summary)
    }

    private fun readingTools(): List<ToolDefinition> =
        listOf(
            ToolDefinition(
                LOOK,
                "Look at the game. Returns the scene ID and every object with its ID, name, component values and rules " +
                    "as blocks (in the same form the rule tools take). Call it first, and again after changes.",
                schema(),
            ),
            ToolDefinition(
                COMPONENTS,
                "Describe the installed components and their properties (IDs, kinds, limits and defaults).",
                schema(),
            ),
            ToolDefinition(
                CHECK,
                "Check the game in the real engine, exactly as Play does, and list rule problems. Call it before finishing.",
                schema(),
            ),
            ToolDefinition(
                TRACE,
                "Play the game headlessly in the real engine and report which rules ran, when, and which conditions were " +
                    "never true. Use it to test changes and to explain why a rule did not fire.",
                schema(
                    "seconds" to number("How long to play, up to $MAX_TRACE_SECONDS seconds. Default 3."),
                    "hold" to
                        JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("array"),
                                "description" to JsonPrimitive("Input actions held while playing, for example input:right."),
                                "items" to
                                    schema(
                                        "action" to
                                            JsonObject(
                                                mapOf(
                                                    "type" to JsonPrimitive("string"),
                                                    "enum" to JsonArray(engine.actions.keys.map(::JsonPrimitive)),
                                                ),
                                            ),
                                        "from" to number("Second when holding starts."),
                                        "to" to number("Second when holding stops."),
                                        required = listOf("action"),
                                    ),
                            ),
                        ),
                    "objects" to
                        JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("array"),
                                "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "description" to JsonPrimitive("Object IDs to trace; all objects with rules when omitted."),
                            ),
                        ),
                ),
            ),
            ToolDefinition(
                ASK,
                "Ask the creator a clarifying question when the request is ambiguous, instead of guessing. The session " +
                    "waits for the answer.",
                schema(
                    "question" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                    "options" to
                        JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("array"),
                                "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "description" to JsonPrimitive("Short suggested answers."),
                            ),
                        ),
                    required = listOf("question"),
                ),
            ),
            ToolDefinition(
                FINISH,
                "Finish and summarize the proposed changes in the creator's words. Nothing changes in the game until the " +
                    "creator accepts.",
                schema("summary" to JsonObject(mapOf("type" to JsonPrimitive("string"))), required = listOf("summary")),
            ),
        )

    private fun schema(
        vararg properties: Pair<String, JsonElement>,
        required: List<String> = emptyList(),
    ): JsonObject =
        JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(mapOf(*properties)),
                "required" to JsonArray(required.map(::JsonPrimitive)),
                "additionalProperties" to JsonPrimitive(false),
            ),
        )

    private fun number(description: String): JsonObject =
        JsonObject(mapOf("type" to JsonPrimitive("number"), "description" to JsonPrimitive(description)))

    private fun problem(message: String): String =
        JsonObject(mapOf("ok" to JsonPrimitive(false), "problem" to JsonPrimitive(message))).toString()

    private fun kind(kind: ValueKind<*>): String =
        when (kind) {
            is ValueKind.WholeNumber -> "whole number ${kind.min}..${kind.max}"
            is ValueKind.Decimal -> "number ${kind.min}..${kind.max}"
            ValueKind.YesNo -> "yes/no"
            is ValueKind.Text -> "text up to ${kind.maxLength} characters"
            is ValueKind.Choice -> "one of ${kind.options.joinToString { it.id }}"
            ValueKind.Color -> "color such as #FFC107"
            is ValueKind.ObjectReference -> "object (not editable yet)"
        }

    private fun plain(value: Any): JsonElement =
        when (value) {
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Double -> JsonPrimitive(value)
            is JsonElement -> value
            else -> JsonPrimitive(value.toString())
        }

    /** Names of the reading tools. */
    public companion object {
        /** Look at the staged game. */
        public const val LOOK: String = "look_at_game"

        /** Describe installed components. */
        public const val COMPONENTS: String = "describe_components"

        /** Validate in the real engine. */
        public const val CHECK: String = "check_game"

        /** Trace rules by headless play. */
        public const val TRACE: String = "trace_rules"

        /** Ask the creator. */
        public const val ASK: String = "ask_creator"

        /** Finish with a summary. */
        public const val FINISH: String = "finish"

        private const val MAX_TRACE_SECONDS = 120.0
    }
}
