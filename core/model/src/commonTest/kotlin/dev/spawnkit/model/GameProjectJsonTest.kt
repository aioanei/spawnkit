package dev.spawnkit.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
        assertTrue("\"kind\":\"player\"" in json, json)
        assertTrue("\"kind\":\"hazard\"" in json, json)
    }

    @Test
    fun unknownKeysAreIgnored() {
        val json = SpawnJson.encode(sampleProject()).replaceFirst("{", "{\"futureField\":{\"a\":1},")
        assertIs<DecodeResult.Ok>(SpawnJson.decode(json))
    }

    @Test
    fun rejectsNewerSchema() {
        val json = SpawnJson.encode(sampleProject()).replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":2")
        val result = assertIs<DecodeResult.Error>(SpawnJson.decode(json))
        assertTrue("Unsupported schemaVersion 2" in result.message, result.message)
    }

    @Test
    fun missingSchemaVersionIsTypedError() {
        val json = SpawnJson.encode(sampleProject()).replaceFirst("\"schemaVersion\":1,", "")
        assertIs<DecodeResult.Error>(SpawnJson.decode(json))
    }

    @Test
    fun unknownComponentKindIsTypedError() {
        val json = SpawnJson.encode(sampleProject()).replaceFirst("\"kind\":\"hazard\"", "\"kind\":\"laser\"")
        assertIs<DecodeResult.Error>(SpawnJson.decode(json))
    }

    @Test
    fun malformedJsonIsTypedError() {
        assertIs<DecodeResult.Error>(SpawnJson.decode("{ not json"))
        assertIs<DecodeResult.Error>(SpawnJson.decode("[1, 2, 3]"))
    }

    @Test
    fun colorSerializesAsHex() {
        val encoded = SpawnJson.json.encodeToString(ColorRgba.serializer(), ColorRgba(0xFF336699))
        assertEquals("\"#FF336699\"", encoded)
        assertEquals(ColorRgba(0xFF336699), SpawnJson.json.decodeFromString(ColorRgba.serializer(), "\"#FF336699\""))
        assertEquals(ColorRgba(0xFF336699), SpawnJson.json.decodeFromString(ColorRgba.serializer(), "\"#336699\""))
    }
}
