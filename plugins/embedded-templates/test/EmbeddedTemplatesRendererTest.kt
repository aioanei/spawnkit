package dev.spawnkit.build.templates

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class EmbeddedTemplatesRendererTest {
    @Test
    fun escapesKotlinStringSyntaxAndNormalizesLineEndings() {
        // Raw JSON: {"a":"$x\"}  followed by a CRLF line ending.
        val json = "{\"a\":\"\$x\\\"}\r\n"
        val source = renderEmbeddedTemplates("dev.example", listOf(EmbeddedTemplate("my-game", json)))
        assertContains(source, "package dev.example")
        assertContains(source, "\"my-game\" to myGame(),")
        // Expected Kotlin literal: append("{\"a\":\"\$x\\\"}\n")
        assertContains(source, "append(\"{\\\"a\\\":\\\"\\\$x\\\\\\\"}\\n\")")
    }

    @Test
    fun splitsLargeDocumentsIntoBoundedConstants() {
        val source = renderEmbeddedTemplates("p", listOf(EmbeddedTemplate("big", "x".repeat(40_000))))
        assertEquals(3, Regex("append\\(\"x+\"\\)").findAll(source).count())
    }
}
