package dev.spawnkit.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Upgrades raw documents from older schema versions, one version at a time (ADR-0002).
 *
 * To make a breaking format change: bump [CURRENT_SCHEMA_VERSION], add a step keyed by the previous version that
 * transforms the raw JSON, and add a test that decodes a document written in the old format.
 */
public object Migrations {
    /** Step `n` upgrades a version-`n` document to version `n + 1`. */
    private val steps: Map<Int, (JsonObject) -> JsonObject> = emptyMap()

    /** Returns [document] migrated from [fromVersion] to [CURRENT_SCHEMA_VERSION]. */
    public fun migrate(
        document: JsonObject,
        fromVersion: Int,
    ): JsonObject {
        var current = document
        for (version in fromVersion until CURRENT_SCHEMA_VERSION) {
            val step = steps[version] ?: throw IllegalArgumentException("No migration from schemaVersion $version")
            current = step(current)
        }
        return JsonObject(current + ("schemaVersion" to JsonPrimitive(CURRENT_SCHEMA_VERSION)))
    }
}
