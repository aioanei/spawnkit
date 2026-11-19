package dev.spawnkit.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Outcome of decoding a Game Document. Decoding never throws for bad input. */
public sealed interface DecodeResult {
    /** The document was decoded (and migrated if it was older). */
    public data class Ok(
        val project: GameProject,
    ) : DecodeResult

    /** The document could not be decoded; [message] explains why. */
    public data class Error(
        val message: String,
    ) : DecodeResult
}

/** The single JSON configuration for Game Documents (ADR-0002). */
public object SpawnJson {
    /** Shared [Json] instance: unknown keys ignored, defaults encoded, nulls omitted, discriminator `kind`. */
    public val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "kind"
        }

    /** Encodes [project] as compact JSON. */
    public fun encode(project: GameProject): String = json.encodeToString(GameProject.serializer(), project)

    /** Decodes a document, migrating older schema versions and rejecting newer ones. */
    public fun decode(text: String): DecodeResult =
        try {
            val root = json.parseToJsonElement(text) as? JsonObject
            if (root == null) {
                DecodeResult.Error("Document root must be a JSON object")
            } else {
                decodeObject(root)
            }
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException.
            DecodeResult.Error(e.message ?: "Invalid document")
        }

    private fun decodeObject(root: JsonObject): DecodeResult {
        val version = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull
        return when {
            version == null -> {
                DecodeResult.Error("Missing or invalid schemaVersion")
            }

            version > CURRENT_SCHEMA_VERSION -> {
                DecodeResult.Error("Unsupported schemaVersion $version (max $CURRENT_SCHEMA_VERSION); update SpawnKit")
            }

            version < 1 -> {
                DecodeResult.Error("Invalid schemaVersion $version")
            }

            else -> {
                DecodeResult.Ok(json.decodeFromJsonElement(GameProject.serializer(), Migrations.migrate(root, version)))
            }
        }
    }
}
