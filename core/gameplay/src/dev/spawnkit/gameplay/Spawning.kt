package dev.spawnkit.gameplay

import dev.spawnkit.editing.ObjectDoc
import dev.spawnkit.editing.Project
import dev.spawnkit.runtime.EntityHandle
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.abs
import kotlin.math.floor

/**
 * A copy a "Spawn a copy" block asked for: the object tagged [tag], at ([x], [y]), moving at ([vx], [vy]) units per
 * second. The block's effect payload is a header line with the block's arguments, then the x and y the rule computed,
 * one per line.
 */
public data class SpawnRequest(
    /** Tag of the object to copy. */
    public val tag: String,
    /** Where the copy appears. */
    public val x: Double,
    /** Where the copy appears. */
    public val y: Double,
    /** Speed across. */
    public val vx: Double,
    /** Speed down. */
    public val vy: Double,
) {
    /** Encoding of the block arguments. */
    public companion object {
        /** The header line for [action]: its arguments as a JSON object. */
        public fun header(action: Action.Spawn): String =
            buildJsonObject {
                put("tag", JsonPrimitive(action.tag))
                when (val place = action.place) {
                    SpawnPlace.Self -> {
                        put("place", JsonPrimitive("self"))
                    }

                    is SpawnPlace.Tagged -> {
                        put("place", JsonPrimitive("tagged"))
                        put("of", JsonPrimitive(place.tag))
                    }

                    is SpawnPlace.Position -> {
                        put("place", JsonPrimitive("position"))
                        put("x", decimal(place.x))
                        put("y", decimal(place.y))
                    }

                    is SpawnPlace.RandomArea -> {
                        put("place", JsonPrimitive("random"))
                        put("minX", JsonPrimitive(place.minX))
                        put("maxX", JsonPrimitive(place.maxX))
                        put("minY", JsonPrimitive(place.minY))
                        put("maxY", JsonPrimitive(place.maxY))
                    }
                }
                action.direction?.let { put("direction", JsonPrimitive(it.name)) }
                put("speed", decimal(action.speed))
            }.toString()

        /** The block whose [header] this is; throws for anything else. */
        public fun action(header: String): Action.Spawn {
            val json = Json.parseToJsonElement(header).jsonObject

            fun text(key: String) =
                json
                    .getValue(key)
                    .jsonPrimitive
                    .takeIf { it.isString }!!
                    .content

            fun decimal(key: String) = json.getValue(key).jsonPrimitive.doubleOrNull!!

            fun whole(key: String) = json.getValue(key).jsonPrimitive.longOrNull!!
            val place =
                when (text("place")) {
                    "self" -> SpawnPlace.Self
                    "tagged" -> SpawnPlace.Tagged(text("of"))
                    "position" -> SpawnPlace.Position(decimal("x"), decimal("y"))
                    "random" -> SpawnPlace.RandomArea(whole("minX"), whole("maxX"), whole("minY"), whole("maxY"))
                    else -> error("unknown place")
                }
            return Action.Spawn(
                text("tag"),
                place,
                json["direction"]?.let { Direction.valueOf(it.jsonPrimitive.content) },
                decimal("speed"),
            )
        }

        /** The request in an effect [payload], or `null` when it is damaged. */
        public fun parse(payload: String): SpawnRequest? =
            runCatching {
                val (header, x, y) = payload.split('\n')
                val action = action(header)
                val (vx, vy) =
                    when (action.direction) {
                        null -> 0.0 to 0.0
                        Direction.Left -> -action.speed to 0.0
                        Direction.Right -> action.speed to 0.0
                        Direction.Up -> 0.0 to -action.speed
                        Direction.Down -> 0.0 to action.speed
                    }
                SpawnRequest(action.tag, x.toDouble(), y.toDouble(), vx, vy)
            }.getOrNull()
    }
}

/**
 * A test purchase a rule asked for (C12): the block, and the object whose rule asked, so "this object" grants to it.
 * The block's effect payload is its arguments as a JSON object.
 */
public data class PurchaseRequest(
    /** The block. */
    public val action: Action.TestPurchase,
    /** The object whose rule asked. */
    public val entity: EntityHandle,
) {
    /** Encoding of the block arguments. */
    public companion object {
        /** The payload for [action]. */
        public fun header(action: Action.TestPurchase): String =
            buildJsonObject {
                put("product", JsonPrimitive(action.product))
                put("price", JsonPrimitive(action.priceCents))
                when (val target = action.target) {
                    Target.Self -> put("self", JsonPrimitive(true))
                    is Target.Tagged -> put("tag", JsonPrimitive(target.tag))
                }
                put("amount", JsonPrimitive(action.amount))
            }.toString()

        /** The block whose [header] this is; throws for anything else. */
        public fun action(header: String): Action.TestPurchase {
            val json = Json.parseToJsonElement(header).jsonObject
            val target = json["tag"]?.jsonPrimitive?.content?.let { Target.Tagged(it) } ?: Target.Self
            return Action.TestPurchase(
                json.getValue("product").jsonPrimitive.content,
                json.getValue("price").jsonPrimitive.longOrNull!!,
                target,
                json.getValue("amount").jsonPrimitive.longOrNull!!,
            )
        }
    }
}

/**
 * [value] as JSON written the same way on every platform: whole numbers keep a ".0", as on the JVM. Kotlin/JS would
 * write 14.0 as 14, so a block saved on one platform would no longer compile to the same text on another and would
 * stop being recognised as a block.
 */
@OptIn(ExperimentalSerializationApi::class)
private fun decimal(value: Double): JsonElement =
    if (value == floor(value) && abs(value) < 1e15) {
        JsonUnquotedLiteral("${if (value < 0.0 || 1.0 / value < 0.0) "-" else ""}${abs(value).toLong()}.0")
    } else {
        JsonPrimitive(value)
    }

/** What the rules of one tick asked players to do: switch scene, make copies and offer test purchases. */
public data class EngineRequests(
    /** The scene a "Go to scene" block asked for (the last one wins), or `null`. */
    public val scene: String?,
    /** Copies to make, in the order rules asked. */
    public val spawns: List<SpawnRequest>,
    /** Test purchases to offer, in the order rules asked. */
    public val purchases: List<PurchaseRequest> = emptyList(),
)

/** The tag of an authored object, normalised, or `null`. */
internal fun ObjectDoc.tagName(): String? =
    (component("gameplay:tag")?.values?.get("tag") as? String)?.trim()?.lowercase()?.takeIf {
        it.isNotEmpty()
    }

/** Whether an authored object is marked as a template. */
internal fun ObjectDoc.isTemplate(): Boolean = component("gameplay:template")?.let { it.values["template"] as? Boolean ?: true } == true

/** [project] without its template objects (and what is inside them), as it is played. */
internal fun Project.withoutTemplates(): Project =
    copy(
        scenes =
            scenes.map { scene ->
                val left =
                    scene.objects
                        .filter { it.isTemplate() }
                        .flatMap { scene.subtree(it.id) }
                        .toSet()
                if (left.isEmpty()) scene else scene.copy(objects = scene.objects.filter { it.id !in left })
            },
    )
