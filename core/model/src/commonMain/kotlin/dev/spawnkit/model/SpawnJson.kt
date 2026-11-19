package dev.spawnkit.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Outcome of decoding a Game Document. Decoding never throws for bad input. */
public sealed interface DecodeResult {
    /** The document was decoded (and migrated if it was older). */
    public data class Ok(
        val project: GameProject,
    ) : DecodeResult

    /** The document could not be decoded. */
    public sealed interface Error : DecodeResult {
        /** Human-readable explanation, suitable for logs and the editor's error panel. */
        public val message: String
    }

    /** The document was written by a newer SpawnKit; the user should update the app. */
    public data class UnsupportedVersion(
        val found: Long,
        val max: Int,
    ) : Error {
        override val message: String get() = "Unsupported schemaVersion $found (max $max); update SpawnKit"
    }

    /** `schemaVersion` is missing, not a JSON number, or out of range. */
    public data class InvalidVersion(
        override val message: String,
    ) : Error

    /** The JSON is malformed or does not match the schema (e.g. an unknown component kind). */
    public data class Malformed(
        override val message: String,
    ) : Error
}

/** The single JSON configuration for Game Documents (ADR-0002). */
public object SpawnJson {
    /**
     * Shared [Json] instance: unknown keys ignored, defaults encoded, `null` values omitted (so every nullable
     * property must default to `null`), polymorphic discriminator `kind`.
     */
    public val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "kind"
        }

    /**
     * Encodes [project] as compact JSON. The exact text may differ between targets (e.g. `20.0` vs `20` on JS),
     * so never compare or hash encoded text across platforms.
     */
    public fun encode(project: GameProject): String = json.encodeToString(GameProject.serializer(), project)

    /** Decodes a document, migrating older schema versions and rejecting newer ones. Never throws. */
    @Suppress("TooGenericExceptionCaught") // Contract: untrusted input (incl. future migrations) must not throw.
    public fun decode(text: String): DecodeResult =
        try {
            val root = json.parseToJsonElement(text) as? JsonObject
            if (root == null) DecodeResult.Malformed("Document root must be a JSON object") else decodeObject(root)
        } catch (e: Exception) {
            DecodeResult.Malformed(e.message ?: e::class.simpleName ?: "Invalid document")
        }

    private fun decodeObject(root: JsonObject): DecodeResult {
        val primitive = root["schemaVersion"] as? JsonPrimitive
        val version = primitive?.takeUnless { it.isString }?.longOrNull
        return when {
            version == null -> DecodeResult.InvalidVersion("Missing or non-numeric schemaVersion")
            version > CURRENT_SCHEMA_VERSION -> DecodeResult.UnsupportedVersion(version, CURRENT_SCHEMA_VERSION)
            version < 1 -> DecodeResult.InvalidVersion("Invalid schemaVersion $version")
            else -> DecodeResult.Ok(json.decodeFromJsonElement(GameProject.serializer(), Migrations.migrate(root, version.toInt())))
        }
    }
}
