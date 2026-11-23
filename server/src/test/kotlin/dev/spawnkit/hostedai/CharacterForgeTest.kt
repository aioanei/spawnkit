package dev.spawnkit.hostedai

import dev.spawnkit.accounts.Accounts
import dev.spawnkit.accounts.AuthResult
import dev.spawnkit.accounts.CapturedMail
import dev.spawnkit.accounts.testDb
import dev.spawnkit.accounts.testPasswords
import dev.spawnkit.data.AiSpend
import dev.spawnkit.editing.ProjectImages
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Character Forge (C16a, ADR-0013) with a fake image gateway: no live image calls. */
class CharacterForgeTest {
    /** Draws a figure for every style, or refuses or fails the styles it is told to. */
    private class FakeImages : ImageGateway {
        override val provenance = "Fake image-1"
        val refuse = mutableSetOf<CharacterStyle>()
        val fail = mutableSetOf<CharacterStyle>()
        val calls = mutableListOf<CharacterStyle>()

        override suspend fun character(
            photo: ByteArray,
            style: CharacterStyle,
        ): ImageResult {
            calls += style
            if (style in fail) error("provider is down")
            if (style in refuse) return ImageResult.Refused("The image model declined this photo. Try a different photo.")
            return ImageResult.Image(figure(1024))
        }
    }

    private val db = testDb()
    private val mail = CapturedMail()
    private val now = Instant.parse("2026-11-23T12:00:00Z").toEpochMilli()
    private val accounts = Accounts(db, mail, testPasswords(), clock = { now })
    private val images = FakeImages()
    private val photo = Base64.getEncoder().encodeToString(png(BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)))

    private fun token(): String {
        accounts.register("ada@example.com", "correct horse", "Ada")
        return (accounts.verify("ada@example.com", mail.lastCode("ada@example.com")) as AuthResult.SignedIn).token
    }

    private fun forge(
        limits: AiLimits = AiLimits(),
        configured: Boolean = true,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        val service = if (configured) CharacterForgeService(images, SpendLedger(db, limits, clock = { now }), clock = { now }) else null
        application { characterRoutes(accounts, service) }
        block()
    }

    private suspend fun HttpClient.make(
        token: String?,
        consent: Boolean = true,
        styles: List<String> = listOf("pixel", "cartoon", "chibi"),
        picture: String = photo,
    ): HttpResponse =
        post("/api/ai/character") {
            token?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody("""{"consent":$consent,"styles":[${styles.joinToString(",") { "\"$it\"" }}],"photo":"$picture"}""")
        }

    private fun spent(): Long = db.tx { AiSpend.selectAll().sumOf { it[AiSpend.microDollars] } }

    @Test
    fun everyStyleBecomesAnImageWithItsProvenanceAndIsCounted() =
        forge {
            val answer = Json.parseToJsonElement(client.make(token()).bodyAsText()).jsonObject
            val made = answer.getValue("images").jsonArray.map { it.jsonObject }

            assertEquals(listOf("pixel", "cartoon", "chibi"), made.map { it.getValue("style").jsonPrimitive.content })
            made.forEach { assertEquals("Fake image-1, 2026-11-23", it.getValue("provenance").jsonPrimitive.content) }
            val sides = made.map { ProjectImages.check(it.getValue("data").jsonPrimitive.content) }
            assertEquals(256, sides[0].width, "pixel art is a 32 × 32 grid scaled 8 ×")
            assertEquals(512, sides[1].width, "other styles are scaled to fit the project limits")
            assertEquals(3 * CharacterForgeService.DEFAULT_MICROS_PER_IMAGE, spent())
        }

    @Test
    fun consentAndSignInAreRequired() =
        forge {
            assertEquals(HttpStatusCode.Unauthorized, client.make(null).status)
            val token = token()
            val noConsent = client.make(token, consent = false)
            assertEquals(HttpStatusCode.BadRequest, noConsent.status)
            assertTrue(noConsent.bodyAsText().contains("Confirm that this is you"))
            assertEquals(HttpStatusCode.BadRequest, client.make(token, styles = listOf("photoreal")).status)
            assertEquals(
                HttpStatusCode.BadRequest,
                client.make(token, picture = Base64.getEncoder().encodeToString("not a png".toByteArray())).status,
            )
            assertEquals(emptyList(), images.calls, "nothing reached the image model")
        }

    @Test
    fun refusalsAndFailuresAreShownPlainlyAndCostNothing() =
        forge {
            images.refuse += CharacterStyle.Cartoon
            images.fail += CharacterStyle.Chibi
            val answer = Json.parseToJsonElement(client.make(token()).bodyAsText()).jsonObject

            assertEquals(1, answer.getValue("images").jsonArray.size)
            val reasons =
                answer.getValue("refused").jsonArray.associate {
                    it.jsonObject
                        .getValue("style")
                        .jsonPrimitive.content to
                        it.jsonObject
                            .getValue("reason")
                            .jsonPrimitive.content
                }
            assertEquals("The image model declined this photo. Try a different photo.", reasons["cartoon"])
            assertTrue(reasons.getValue("chibi").contains("could not be made"))
            assertEquals(CharacterForgeService.DEFAULT_MICROS_PER_IMAGE, spent(), "only the image that was made is counted")
        }

    @Test
    fun theSharedLimitsStopImageCalls() =
        forge(limits = AiLimits(dailyMicros = 2 * CharacterForgeService.DEFAULT_MICROS_PER_IMAGE)) {
            val token = token()
            val answer = Json.parseToJsonElement(client.make(token).bodyAsText()).jsonObject
            assertEquals(2, answer.getValue("images").jsonArray.size)
            assertEquals(
                "chibi",
                answer
                    .getValue("refused")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("style")
                    .jsonPrimitive.content,
            )

            assertEquals(HttpStatusCode.TooManyRequests, client.make(token, styles = listOf("pixel")).status, "the day's allowance is used")
        }

    @Test
    fun aServerWithoutTheImageKeySaysSo() =
        forge(configured = false) {
            val answer = client.make(token())
            assertEquals(HttpStatusCode.ServiceUnavailable, answer.status)
            assertTrue(answer.bodyAsText().contains("not set up"))
        }

    @Test
    fun pixelArtIsDeterministicCrispAndUsesFewColours() {
        val first = PixelArt.pixelate(figure(1024))
        assertContentEquals(first, PixelArt.pixelate(figure(1024)), "the same picture always gives the same pixels")
        val image = ImageIO.read(first.inputStream())
        assertEquals(256, image.width)
        val colours = mutableSetOf<Int>()
        for (cell in 0 until 32 * 32) {
            val x0 = cell % 32 * 8
            val y0 = cell / 32 * 8
            val colour = image.getRGB(x0, y0)
            for (y in y0 until y0 + 8) for (x in x0 until x0 + 8) assertEquals(colour, image.getRGB(x, y), "cell $cell is one colour")
            val alpha = colour ushr 24
            assertTrue(alpha == 0 || alpha == 255, "transparency is all or nothing")
            if (alpha == 255) colours += colour
        }
        assertTrue(colours.size in 2..16, "a small palette: ${colours.size} colours")
        assertEquals(0, image.getRGB(0, 0) ushr 24, "the transparent background stays transparent")
    }

    private companion object {
        /** A figure on a transparent background: a skin-coloured head over a blue body, with a gradient. */
        fun figure(side: Int): ByteArray {
            val image = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until side) {
                for (x in 0 until side) {
                    val inHead = (x - side / 2) * (x - side / 2) + (y - side / 3) * (y - side / 3) < (side / 8) * (side / 8)
                    val inBody = x in side * 3 / 8 until side * 5 / 8 && y in side / 2 until side * 7 / 8
                    val colour =
                        when {
                            inHead -> 0xFFF2C29A.toInt()
                            inBody -> (0xFF000000.toInt()) or (0x20 shl 16) or ((0x40 + y * 0x60 / side) shl 8) or 0xE0
                            else -> 0
                        }
                    image.setRGB(x, y, colour)
                }
            }
            return png(image)
        }

        fun png(image: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
}
