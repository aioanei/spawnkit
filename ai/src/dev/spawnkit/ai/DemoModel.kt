package dev.spawnkit.ai

import dev.spawnkit.gameplay.StandardActions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * A keyless, labelled stand-in for a model, so creators without an API key can see the assistant work (C7c). A fixed
 * script handles only [PROMPTS]. It reads the real game through `look_at_game` and stages changes through the same
 * tools a live model uses, so IDs, validation, previews and undo are real; only the choices are scripted. It never
 * calls a model or the network and is always labelled with [LABEL] (AGENTS.md: replays and demos stay labelled).
 */
public class DemoModel : AgentModel {
    override val info: ModelInfo = ModelInfo("Scripted demo", "none", live = false, label = LABEL)

    private var calls = 0

    override suspend fun reply(request: ModelRequest): ModelReply {
        val start = request.messages.indexOfLast { it is AgentMessage.User }
        val prompt = (request.messages.getOrNull(start) as? AgentMessage.User)?.text.orEmpty()
        val after = request.messages.drop(start + 1)
        val step = after.count { it is AgentMessage.Assistant }
        val known = PROMPTS.indexOfFirst { it.equals(prompt.trim(), ignoreCase = true) }
        if (known < 0) return finish(UNKNOWN)
        return when (step) {
            0 -> reply(call(AgentToolbox.LOOK, JsonObject(emptyMap())))
            1 -> act(known, toolText(after, AgentToolbox.LOOK))
            else -> finish(if (known == WHY) toolText(after, AgentToolbox.TRACE) else SUMMARIES.getValue(known))
        }
    }

    private fun act(
        prompt: Int,
        look: String,
    ): ModelReply {
        val game = Json.parseToJsonElement(look).jsonObject
        val scene = game.string("scene")
        val objects = game["objects"]?.jsonArray.orEmpty().map { it.jsonObject }
        val players = objects.tagged("player")
        val coins = objects.tagged("coin")
        val scores = objects.tagged("score")
        if (players.isEmpty() || (prompt != MOVE && (coins.isEmpty() || scores.isEmpty()))) return finish(MISSING)
        val actions =
            when (prompt) {
                COLLECT -> coins.map { coin -> addRule(scene, coin, touching("player"), collect()) }
                MOVE ->
                    listOf(
                        StandardActions.LEFT to "Left",
                        StandardActions.RIGHT to "Right",
                        StandardActions.UP to "Up",
                        StandardActions.DOWN to "Down",
                    ).map { (action, direction) -> addRule(scene, players.first(), keyHeld(action), listOf(move(direction))) }
                else -> listOf(call(AgentToolbox.TRACE, json("""{"seconds":3,"hold":[{"action":"${StandardActions.RIGHT}"}]}""")))
            }
        val check = if (prompt == WHY) emptyList() else listOf(call(AgentToolbox.CHECK, JsonObject(emptyMap())))
        return reply(*(actions + check).toTypedArray())
    }

    private fun addRule(
        scene: String,
        target: JsonObject,
        whenBlock: JsonObject,
        doBlocks: List<JsonObject>,
    ) = call(
        "add_rule",
        JsonObject(
            mapOf(
                "scene" to JsonPrimitive(scene),
                "object" to JsonPrimitive(target.string("id")),
                "when" to whenBlock,
                "do" to JsonArray(doBlocks),
            ),
        ),
    )

    private fun touching(tag: String) = json("""{"block":"Touching","tag":"$tag"}""")

    private fun keyHeld(action: String) = json("""{"block":"KeyHeld","action":"$action"}""")

    private fun move(direction: String) = json("""{"block":"Move","direction":"$direction","speed":3}""")

    private fun collect() =
        listOf(
            json("""{"block":"ChangeNumber","target":{"kind":"tagged","tag":"score"},"amount":1}"""),
            json("""{"block":"ShowNumber","target":{"kind":"tagged","tag":"score"},"prefix":"Score: "}"""),
            json("""{"block":"RemoveSelf"}"""),
        )

    private fun finish(summary: String) = reply(call(AgentToolbox.FINISH, JsonObject(mapOf("summary" to JsonPrimitive(summary)))))

    private fun reply(vararg toolCalls: ToolCall) = ModelReply(null, toolCalls.toList())

    private fun call(
        name: String,
        input: JsonObject,
    ) = ToolCall("demo-${calls++}", name, input)

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun toolText(
        after: List<AgentMessage>,
        name: String,
    ): String =
        after
            .filterIsInstance<AgentMessage.ToolReplies>()
            .flatMap { it.replies }
            .last { it.name == name }
            .text

    private fun List<JsonObject>.tagged(tag: String): List<JsonObject> =
        filter { obj ->
            val tagComponent = obj["components"]?.jsonObject?.get("gameplay:tag") as? JsonObject
            tagComponent?.get("tag")?.text() == tag
        }

    private fun JsonObject.string(key: String): String = getValue(key).text().orEmpty()

    private fun JsonElement.text(): String? = (this as? JsonPrimitive)?.contentOrNull

    /** The script's label and prompts. */
    public companion object {
        /** Shown wherever the demo answers. */
        public const val LABEL: String = "Scripted demo: a fixed script stands in for the AI model (no model is called)"

        /** The only prompts the script handles. */
        public val PROMPTS: List<String> =
            listOf(
                "When the player touches a coin, add 1 to the score and remove the coin.",
                "Make the player move with the arrow keys.",
                "Why doesn't my coin rule work?",
            )

        private const val COLLECT = 0
        private const val MOVE = 1
        private const val WHY = 2

        private val SUMMARIES =
            mapOf(
                COLLECT to "When the player touches a coin, the score goes up by 1, the score text updates and the coin disappears.",
                MOVE to "The player now moves while you hold the arrow keys.",
            )

        private const val MISSING = "This demo needs a Player, a Coin and a Score. Add them from the palette, then ask again."
        private const val UNKNOWN =
            "The scripted demo only knows its example prompts. Add an Anthropic API key in the assistant settings for any request."
    }
}
