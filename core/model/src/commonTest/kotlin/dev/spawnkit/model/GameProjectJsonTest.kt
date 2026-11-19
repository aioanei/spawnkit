package dev.spawnkit.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameProjectJsonTest {
    @Test
    fun roundTripIsLossless() {
        val project = sampleProject()
        val decoded = SpawnJson.decode(SpawnJson.encode(project))
        assertEquals(DecodeResult.Ok(project), decoded)
    }

    @Test
    fun componentDiscriminatorIsKind() {
        val json = SpawnJson.encode(sampleProject())
        assertTrue("{\"kind\":\"player\"" in json, json)
        assertTrue("\"components\":[{\"kind\":\"hazard\"}]" in json, json)
    }

    @Test
    fun tilesetUsesBehaviorNotKind() {
        val json = SpawnJson.encode(sampleProject())
        assertTrue("\"#\":{\"behavior\":\"solid\"" in json, json)
    }

    @Test
    fun unknownKeysAreIgnored() {
        val json = SpawnJson.encode(sampleProject()).replaceFirst("{", "{\"futureField\":{\"a\":1},")
        assertIs<DecodeResult.Ok>(SpawnJson.decode(json))
    }

    @Test
    fun rejectsNewerSchema() {
        val json = SpawnJson.encode(sampleProject()).replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":2")
        assertEquals(DecodeResult.UnsupportedVersion(found = 2, max = CURRENT_SCHEMA_VERSION), SpawnJson.decode(json))
    }

    @Test
    fun hugeSchemaVersionIsReportedAsNewer() {
        val json = SpawnJson.encode(sampleProject()).replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":3000000000")
        assertIs<DecodeResult.UnsupportedVersion>(SpawnJson.decode(json))
    }

    @Test
    fun missingOrStringSchemaVersionIsInvalid() {
        val encoded = SpawnJson.encode(sampleProject())
        assertIs<DecodeResult.InvalidVersion>(SpawnJson.decode(encoded.replaceFirst("\"schemaVersion\":1,", "")))
        assertIs<DecodeResult.InvalidVersion>(SpawnJson.decode(encoded.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":\"1\"")))
        assertIs<DecodeResult.InvalidVersion>(SpawnJson.decode(encoded.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":0")))
    }

    @Test
    fun unknownComponentKindIsMalformed() {
        val result = SpawnJson.decode(minimalDocument(entityComponents = """[{"kind":"laser"}]"""))
        val error = assertIs<DecodeResult.Malformed>(result)
        assertTrue("laser" in error.message, error.message)
    }

    @Test
    fun knownComponentKindInMinimalDocumentDecodes() {
        val result = SpawnJson.decode(minimalDocument(entityComponents = """[{"kind":"goal"}]"""))
        val project = assertIs<DecodeResult.Ok>(result).project
        assertEquals(
            listOf<Component>(Goal),
            project.scenes
                .single()
                .entities
                .single()
                .components,
        )
    }

    @Test
    fun malformedJsonIsMalformed() {
        assertIs<DecodeResult.Malformed>(SpawnJson.decode("{ not json"))
        assertIs<DecodeResult.Malformed>(SpawnJson.decode("[1, 2, 3]"))
        assertIs<DecodeResult.Malformed>(SpawnJson.decode(""))
    }

    @Test
    fun multiCharacterTilesetKeyIsMalformed() {
        val json =
            minimalDocument(
                entityComponents = "[]",
            ).replace("\"tileset\":{}", "\"tileset\":{\"##\":{\"behavior\":\"solid\",\"color\":\"#FF000000\"}}")
        assertIs<DecodeResult.Malformed>(SpawnJson.decode(json))
    }

    @Test
    fun colorSerializesAsHex() {
        val encoded = SpawnJson.json.encodeToString(ColorRgba.serializer(), ColorRgba(0xFF336699))
        assertEquals("\"#FF336699\"", encoded)
        assertEquals(ColorRgba(0xFF336699), SpawnJson.json.decodeFromString(ColorRgba.serializer(), "\"#FF336699\""))
        assertEquals(ColorRgba(0xFF336699), SpawnJson.json.decodeFromString(ColorRgba.serializer(), "\"#336699\""))
    }

    @Test
    fun malformedColorsAreRejected() {
        listOf("336699", "#12345", "#-1234567", "#-12345", "#+1234567", "#GG336699", "#", "#FF33669").forEach {
            assertNull(ColorRgba.parseOrNull(it), it)
        }
        assertFailsWith<SerializationException> {
            SpawnJson.json.decodeFromString(ColorRgba.serializer(), "\"#nothex\"")
        }
    }

    @Test
    fun migrationRejectsOutOfRangeVersions() {
        val document = SpawnJson.json.parseToJsonElement(SpawnJson.encode(sampleProject())).jsonObject
        assertFailsWith<IllegalArgumentException> { Migrations.migrate(document, fromVersion = CURRENT_SCHEMA_VERSION + 1) }
        assertFailsWith<IllegalArgumentException> { Migrations.migrate(document, fromVersion = 0) }
        val migrated: JsonObject = Migrations.migrate(document, fromVersion = CURRENT_SCHEMA_VERSION)
        assertEquals(JsonPrimitive(CURRENT_SCHEMA_VERSION), migrated["schemaVersion"])
    }

    @Test
    fun nonFiniteGeometryIsRejected() {
        assertFailsWith<IllegalArgumentException> { Vec2(Double.NaN, 0.0) }
        assertFailsWith<IllegalArgumentException> { Size2(1.0, Double.POSITIVE_INFINITY) }
    }

    private fun minimalDocument(entityComponents: String): String =
        """
        {"schemaVersion":1,"id":"p","meta":{"name":"P"},"settings":{"startScene":"s"},"tileset":{},
         "scenes":[{"id":"s","name":"S","size":{"width":1,"height":1},"background":"#FF000000",
           "entities":[{"id":"e","name":"E","position":{"x":0,"y":0},"components":$entityComponents}]}]}
        """.trimIndent()
}
