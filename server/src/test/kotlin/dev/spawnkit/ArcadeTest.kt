package dev.spawnkit

import dev.spawnkit.editing.ComponentDoc
import dev.spawnkit.editing.GamePackage
import dev.spawnkit.editing.GamePackageCodec
import dev.spawnkit.editing.ObjectDoc
import dev.spawnkit.editing.Project
import dev.spawnkit.editing.SceneDoc
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArcadeTest {
    private val packageText = GamePackageCodec.encode(GamePackage.of(Project.blank("Space \"Run\" 🚀")))

    @Test
    fun publishedGameCanBeListedAndPlayedAfterRestart() =
        withStore { directory ->
            lateinit var url: String
            testApplication {
                application { arcade(ArcadeStore(directory), playerFixture()) }
                val published = client.publish(packageText)
                assertEquals(HttpStatusCode.Created, published.status)
                val metadata = Json.parseToJsonElement(published.bodyAsText()).jsonObject
                val slug = metadata.getValue("slug").jsonPrimitive.content
                url = metadata.getValue("playUrl").jsonPrimitive.content
                assertEquals("/play/$slug/", url)
                assertEquals(url, published.headers[HttpHeaders.Location])
                assertEquals("Space \"Run\" 🚀", metadata.getValue("title").jsonPrimitive.content)
                assertEquals(listOf(metadata), Json.parseToJsonElement(client.get("/api/games").bodyAsText()).jsonArray.toList())
                assertEquals(packageText, client.get("/api/games/$slug").bodyAsText())
                assertEquals(packageText, client.get("/api/games/$slug?slug=unused").bodyAsText())
                val noRedirect = createClient { followRedirects = false }
                val redirect = noRedirect.get(url.removeSuffix("/"))
                assertEquals(HttpStatusCode.Found, redirect.status)
                assertEquals(url, redirect.headers[HttpHeaders.Location])
            }
            testApplication {
                application { arcade(ArcadeStore(directory), playerFixture()) }
                assertEquals("index", client.get(url).bodyAsText())
                assertEquals("index", client.get("$url?asset=unused").bodyAsText())
                assertEquals(packageText, client.get("${url}game.spawnkit.json").bodyAsText())
                val script = client.get("${url}nested/player.mjs")
                assertEquals("script", script.bodyAsText())
                assertEquals("text/javascript", script.contentType()?.withoutParameters().toString())
                assertEquals(
                    "application/wasm",
                    client
                        .get("${url}player.wasm")
                        .contentType()
                        ?.withoutParameters()
                        .toString(),
                )
                assertEquals(HttpStatusCode.NotFound, client.get("${url}unknown").status)
                assertEquals(HttpStatusCode.NotFound, client.get("/play/missing/index.html").status)
            }
        }

    @Test
    fun invalidAndIncompatiblePackagesDoNotEnterTheArcade() =
        withStore { directory ->
            testApplication {
                application { arcade(ArcadeStore(directory), playerFixture()) }
                assertEquals(HttpStatusCode.BadRequest, client.publish("{}").status)
                val missing = ObjectDoc("object-2", "Ship", components = listOf(ComponentDoc("extension:wings")))
                val project = Project("Flight", listOf(SceneDoc("scene-1", "Sky", listOf(missing))), nextId = 3)
                val incompatible = client.publish(GamePackageCodec.encode(GamePackage.of(project)))
                assertEquals(HttpStatusCode.UnprocessableEntity, incompatible.status)
                assertContains(incompatible.bodyAsText(), "extension:wings")
                val wrongMedia = client.post("/api/games") { setBody(packageText) }
                assertEquals(HttpStatusCode.UnsupportedMediaType, wrongMedia.status)
                assertEquals("[]", client.get("/api/games").bodyAsText())
            }
        }

    @Test
    fun enforcesByteLimitForSizedAndStreamingUploadsAndLimitsAttempts() =
        withStore { directory ->
            var now = 0L
            val limits = PublishLimits(bytes = 64, perAddress = 2, clockMillis = { now })
            testApplication {
                application { arcade(ArcadeStore(directory), playerFixture(), limits) }
                assertEquals(HttpStatusCode.PayloadTooLarge, client.publish(packageText).status)
                val stream =
                    object : OutgoingContent.WriteChannelContent() {
                        override val contentType = ContentType.Application.Json

                        override suspend fun writeTo(channel: ByteWriteChannel) = channel.writeFully(packageText.encodeToByteArray())
                    }
                assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/api/games") { setBody(stream) }.status)
                val throttled = client.publish("{}")
                assertEquals(HttpStatusCode.TooManyRequests, throttled.status)
                assertEquals("60", throttled.headers[HttpHeaders.RetryAfter])
                now += 60_000
                assertEquals(HttpStatusCode.BadRequest, client.publish("{}").status)
                assertEquals("[]", client.get("/api/games").bodyAsText())
            }
        }

    @Test
    fun fullStoreAndMissingPlayerExplainWhyPublishingIsUnavailable() =
        withStore { directory ->
            testApplication {
                application { arcade(ArcadeStore(directory), WebPlayerBundle { null }) }
                val result = client.publish(packageText)
                assertEquals(HttpStatusCode.ServiceUnavailable, result.status)
                assertContains(result.bodyAsText(), "web player")
                assertEquals("[]", client.get("/api/games").bodyAsText())
            }
            testApplication {
                application { arcade(ArcadeStore(directory, maxGames = 1), playerFixture()) }
                assertEquals(HttpStatusCode.Created, client.publish(packageText).status)
                assertEquals(HttpStatusCode.ServiceUnavailable, client.publish(packageText).status)
                assertEquals(1, Json.parseToJsonElement(client.get("/api/games").bodyAsText()).jsonArray.size)
            }
        }

    @Test
    fun defaultUploadLimitCoversEveryPackageTheBuilderCanProduce() {
        // Packages are limited in UTF-16 characters; a character can take up to three UTF-8 bytes on the wire.
        assertTrue(PublishLimits().bytes >= 3L * GamePackageCodec.MAX_PACKAGE_CHARS)
        val nonAscii = GamePackageCodec.encode(GamePackage.of(Project.blank("ゲーム ".repeat(10))))
        assertTrue(nonAscii.encodeToByteArray().size > nonAscii.length)
    }

    @Test
    fun aDamagedStoreFileDoesNotHideOtherGames() =
        withStore { directory ->
            testApplication {
                application { arcade(ArcadeStore(directory), playerFixture()) }
                assertEquals(HttpStatusCode.Created, client.publish(packageText).status)
                Files.write(directory.resolve("notes.game"), byteArrayOf(0x7F))
                val listing = client.get("/api/games")
                assertEquals(HttpStatusCode.OK, listing.status)
                assertEquals(1, Json.parseToJsonElement(listing.bodyAsText()).jsonArray.size)
            }
        }

    @Test
    fun globalRateLimitBoundsTheNumberOfAddresses() {
        var now = 10L
        val limits = PublishLimits(perAddress = 1, perServer = 2, clockMillis = { now })
        assertTrue(limits.allow("a"))
        assertFalse(limits.allow("a"))
        assertTrue(limits.allow("b"))
        assertFalse(limits.allow("c"))
        now += 60_000
        assertTrue(limits.allow("c"))
    }

    private suspend fun HttpClient.publish(text: String) =
        post("/api/games") {
            contentType(ContentType.Application.Json)
            setBody(text)
        }

    private fun playerFixture(): WebPlayerBundle {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, data) in mapOf("index.html" to "index", "nested/player.mjs" to "script", "player.wasm" to "wasm")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return WebPlayerBundle { bytes.toByteArray().inputStream() }
    }

    private fun withStore(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("spawnkit-arcade-test")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
