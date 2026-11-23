package dev.spawnkit.editing

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Versioned, portable workspace files. This boundary encodes authoring data, never runtime worlds or handles.
 * Unknown components and opaque JSON property values survive without installing their capabilities. Unsupported
 * versions, fields or instructions fail explicitly instead of silently discarding data.
 */
public object ProjectCodec {
    /**
     * Current workspace-file schema, separate from the legacy platformer document schema. Schema 2 added `images`
     * (ADR-0012) and schema 3 `ads` (C12); older files are migrated when they are opened.
     */
    public const val SCHEMA_VERSION: Int = 3

    /** Maximum UTF-16 characters in a project file, also used by platform readers before decoding. */
    public const val MAX_FILE_CHARS: Int = 10 * 1024 * 1024

    /** Encodes a structurally valid project, preserving typed values and rule source locations. */
    public fun encode(project: Project): String {
        requireValid(project)
        val result =
            jsonObject(
                "format" to JsonPrimitive("spawnkit-project"),
                "schemaVersion" to JsonPrimitive(SCHEMA_VERSION),
                "name" to JsonPrimitive(project.name),
                "nextId" to JsonPrimitive(project.nextId),
                "scenes" to
                    JsonArray(
                        project.scenes.map { scene ->
                            jsonObject(
                                "id" to JsonPrimitive(scene.id),
                                "name" to JsonPrimitive(scene.name),
                                "objects" to
                                    JsonArray(
                                        scene.objects.map { obj ->
                                            jsonObject(
                                                "id" to JsonPrimitive(obj.id),
                                                "name" to JsonPrimitive(obj.name),
                                                "parent" to (obj.parent?.let(::JsonPrimitive) ?: JsonNull),
                                                "components" to
                                                    JsonArray(
                                                        obj.components.map { component ->
                                                            jsonObject(
                                                                "id" to JsonPrimitive(component.componentId),
                                                                "values" to
                                                                    JsonObject(
                                                                        component.values.mapValues { (_, value) ->
                                                                            encodeProperty(value)
                                                                        },
                                                                    ),
                                                            )
                                                        },
                                                    ),
                                                "rules" to JsonArray(obj.rules.map(RuleJson::encode)),
                                            )
                                        },
                                    ),
                            )
                        },
                    ),
                "ads" to
                    jsonObject(
                        "banner" to JsonPrimitive(project.ads.banner),
                        "interstitial" to JsonPrimitive(project.ads.interstitial),
                        "every" to JsonPrimitive(project.ads.every),
                    ),
                "images" to
                    JsonArray(
                        project.images.map { image ->
                            jsonObject(
                                "id" to JsonPrimitive(image.id),
                                "name" to JsonPrimitive(image.name),
                                "hash" to JsonPrimitive(image.hash),
                                "data" to JsonPrimitive(image.data),
                            )
                        },
                    ),
            ).toString()
        checkSizeAndDepth(result)
        return result
    }

    /**
     * Brings an older workspace file up to [SCHEMA_VERSION] as JSON, before it is read: schema 1 gains an empty
     * `images` list, and schema 2 gains `ads`, all off. Newer or unknown versions are returned unchanged and refused by
     * the reader.
     */
    internal fun migrate(root: JsonElement): JsonElement {
        var fields = root as? JsonObject ?: return root
        var version = (fields["schemaVersion"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        if (version == 1L) {
            fields = JsonObject(fields + mapOf("schemaVersion" to JsonPrimitive(2), "images" to JsonArray(emptyList())))
            version = 2L
        }
        if (version == 2L) {
            val ads = jsonObject("banner" to JsonPrimitive(false), "interstitial" to JsonPrimitive(false), "every" to JsonPrimitive(3))
            fields = JsonObject(fields + mapOf("schemaVersion" to JsonPrimitive(3), "ads" to ads))
        }
        return fields
    }

    /** Decodes before touching the current workspace. Throws [IllegalArgumentException] with an explanation. */
    public fun decode(text: String): Project {
        checkSizeAndDepth(text)
        return try {
            readObject(migrate(Json.parseToJsonElement(text))) {
                require(string("format") == "spawnkit-project") { "Choose a SpawnKit project file" }
                val version = integer("schemaVersion")
                require(version == SCHEMA_VERSION.toLong()) { "Project version $version is not supported by this builder" }
                Project(
                    name = string("name"),
                    nextId = integer("nextId"),
                    scenes =
                        array("scenes").map { scene ->
                            readObject(scene) {
                                SceneDoc(
                                    string("id"),
                                    string("name"),
                                    array("objects").map { obj ->
                                        readObject(obj) {
                                            ObjectDoc(
                                                string("id"),
                                                string("name"),
                                                nullableString("parent"),
                                                array("components").map { component ->
                                                    readObject(component) {
                                                        ComponentDoc(
                                                            string("id"),
                                                            objectValue("values").mapValues { (_, value) -> decodeProperty(value) },
                                                        )
                                                    }
                                                },
                                                array("rules").map(RuleJson::decode),
                                            )
                                        }
                                    },
                                )
                            }
                        },
                    images = array("images").map { image -> readObject(image) { readImage() } },
                    ads = readObject(get("ads")) { readAds() },
                ).also(::requireValid)
            }
        } catch (problem: Exception) {
            val detail = if (problem is SerializationException) "The project file is incomplete or damaged" else problem.message
            throw IllegalArgumentException("This project cannot be opened: ${detail ?: "the file is incomplete"}", problem)
        }
    }

    private fun JsonFields.readAds(): AdSettings {
        fun flag(name: String) = requireNotNull((get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull) { "The ad settings are damaged" }
        return AdSettings(flag("banner"), flag("interstitial"), integer("every").toInt())
    }

    private fun JsonFields.readImage(): ImageAsset {
        val image = ImageAsset(string("id"), string("name"), string("hash"), string("data"))
        val info =
            try {
                ProjectImages.check(image.data)
            } catch (problem: IllegalArgumentException) {
                throw IllegalArgumentException("The image ${image.name} cannot be used: ${problem.message}", problem)
            }
        require(info.hash == image.hash) { "The image ${image.name} is damaged" }
        return image
    }

    private fun requireValid(project: Project) {
        require(project.nextId in 1 until Long.MAX_VALUE) { "The project's identifier counter is invalid" }
        require(project.name.isNotBlank()) { "Give the project a name" }
        val named =
            project.scenes.all { scene ->
                scene.id.isNotBlank() && scene.name.isNotBlank() && scene.objects.all { it.id.isNotBlank() && it.name.isNotBlank() }
            }
        require(named) {
            "Every scene and object needs a name and an identifier"
        }
        // Parents are flat file data; bound graph depth before recursive editor presentation.
        for (scene in project.scenes) {
            val parents = scene.objects.associate { it.id to it.parent }
            for (obj in scene.objects) {
                var parent = obj.parent
                var depth = 0
                while (parent != null) {
                    require(++depth <= 128) { "${obj.name}'s hierarchy has a cycle or more than 128 levels" }
                    parent = parents[parent]
                }
            }
        }
        val problems = project.problems()
        require(problems.isEmpty()) { problems.joinToString("; ") }
        require(project.scenes.all { scene -> scene.objects.all { obj -> obj.components.all { it.componentId.isNotBlank() } } }) {
            "A component's identifier is missing"
        }
        require(project.maxIdSuffix() < Long.MAX_VALUE - 1) { "The project's identifiers are out of range" }
    }

    private fun encodeProperty(value: Any): JsonObject =
        when (value) {
            is Long -> {
                tagged("integer", JsonPrimitive(value))
            }

            is Double -> {
                require(value.isFinite()) { "A property contains a number that cannot be saved" }
                tagged("decimal", decimalJson(value))
            }

            is Boolean -> {
                tagged("boolean", JsonPrimitive(value))
            }

            is String -> {
                tagged("text", JsonPrimitive(value))
            }

            is JsonElement -> {
                tagged("opaque", value)
            }

            else -> {
                throw IllegalArgumentException("A property has no portable value codec")
            }
        }

    private fun decodeProperty(element: JsonElement): Any =
        readObject(element) {
            val kind = string("kind")
            val value = get("value")
            when (kind) {
                "integer" -> {
                    requireNotNull((value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull) { "A whole number is invalid" }
                }

                "decimal" -> {
                    requireNotNull(
                        (value as? JsonPrimitive)
                            ?.takeUnless {
                                it.isString
                            }?.doubleOrNull
                            ?.takeIf { it.isFinite() },
                    ) { "A decimal number is invalid" }
                }

                "boolean" -> {
                    requireNotNull(
                        (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull,
                    ) { "A yes/no value is invalid" }
                }

                "text" -> {
                    requireNotNull((value as? JsonPrimitive)?.takeIf { it.isString }?.content) { "A text value is invalid" }
                }

                "opaque" -> {
                    value
                }

                else -> {
                    throw IllegalArgumentException("A property uses an unsupported value encoding: $kind")
                }
            }
        }

    private fun tagged(
        kind: String,
        value: JsonElement,
    ) = jsonObject("kind" to JsonPrimitive(kind), "value" to value)

    /** Bounds the parser's stack before JSON parsing; brackets inside strings and escapes do not count. */
    private fun checkSizeAndDepth(text: String) {
        require(text.length <= MAX_FILE_CHARS) { "This project is too large (maximum $MAX_FILE_CHARS characters)" }
        checkJsonDepth(text)
    }

    internal fun checkJsonDepth(
        text: String,
        extraLevels: Int = 0,
    ) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in text) {
            if (quoted) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    quoted = false
                }
            } else {
                when (char) {
                    '"' -> {
                        quoted = true
                    }

                    '{', '[' -> {
                        depth++
                        require(depth <= 128 + extraLevels) { "This project's data is nested too deeply" }
                    }

                    '}', ']' -> {
                        depth--
                        require(depth >= 0) { "This project's data is incomplete" }
                    }
                }
            }
        }
    }
}

internal fun jsonObject(vararg entries: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*entries))

/** JS number-to-string conversion erases negative zero; keep its JSON token explicit on every target. */
internal fun decimalJson(value: Double): JsonElement =
    if (value == 0.0 && value.toBits() == Long.MIN_VALUE) Json.parseToJsonElement("-0.0") else JsonPrimitive(value)

/** Rejects extra fields as well as missing ones, so newer data is not silently discarded on an older builder. */
internal class JsonFields(
    element: JsonElement,
) {
    private val fields = requireNotNull(element as? JsonObject) { "An object is incomplete" }
    private val used = mutableSetOf<String>()

    fun get(name: String): JsonElement {
        used += name
        return requireNotNull(fields[name]) { "The '$name' field is missing" }
    }

    fun string(name: String): String =
        requireNotNull(
            (get(name) as? JsonPrimitive)
                ?.takeIf {
                    it.isString
                }?.content,
        ) { "The '$name' field needs text" }

    fun integer(name: String): Long =
        requireNotNull(
            (get(name) as? JsonPrimitive)
                ?.takeUnless {
                    it.isString
                }?.longOrNull,
        ) { "The '$name' field needs a whole number" }

    fun array(name: String): JsonArray = requireNotNull(get(name) as? JsonArray) { "The '$name' list is incomplete" }

    fun objectValue(name: String): JsonObject = requireNotNull(get(name) as? JsonObject) { "The '$name' values are incomplete" }

    fun nullableString(name: String): String? =
        get(name).let {
            if (it ==
                JsonNull
            ) {
                null
            } else {
                requireNotNull((it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content) { "The '$name' field needs text" }
            }
        }

    fun finish() {
        require(fields.keys == used) { "This file has fields this builder does not understand: ${(fields.keys - used).joinToString()}" }
    }
}

internal inline fun <T> readObject(
    element: JsonElement,
    read: JsonFields.() -> T,
): T {
    val fields = JsonFields(element)
    val result = fields.read()
    fields.finish()
    return result
}
