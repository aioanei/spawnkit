package dev.spawnkit.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Upgrades raw documents from older schema versions, one version at a time (ADR-0002).
 *
 * To make a breaking format change: bump [CURRENT_SCHEMA_VERSION], add a step keyed by the previous version that
 * transforms the raw JSON, and add a test that decodes a document written in the old format. Steps receive
 * untrusted JSON; [SpawnJson.decode] turns any exception they throw into a [DecodeResult.Malformed].
 */
internal object Migrations {
    /** Step `n` upgrades a version-`n` document to version `n + 1`. */
    private val steps: Map<Int, (JsonObject) -> JsonObject> = emptyMap()

    /** Returns [document] migrated from [fromVersion] (1..[CURRENT_SCHEMA_VERSION]) to [CURRENT_SCHEMA_VERSION]. */
    fun migrate(
        document: JsonObject,
        fromVersion: Int,
    ): JsonObject {
        require(fromVersion in 1..CURRENT_SCHEMA_VERSION) { "Cannot migrate from schemaVersion $fromVersion" }
        var current = document
        for (version in fromVersion until CURRENT_SCHEMA_VERSION) {
            val step = requireNotNull(steps[version]) { "No migration from schemaVersion $version" }
            current = step(current)
        }
        return JsonObject(current + ("schemaVersion" to JsonPrimitive(CURRENT_SCHEMA_VERSION)))
    }
}
