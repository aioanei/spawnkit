package dev.spawnkit.hostedai

import dev.spawnkit.ArcadeProblem
import dev.spawnkit.accounts.Accounts
import dev.spawnkit.accounts.signedIn
import dev.spawnkit.editing.ProjectImages
import dev.spawnkit.readLimitedText
import dev.spawnkit.respondProblem
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

/**
 * Character Forge (ADR-0013): one image call per style, each reserved in advance against the shared [ledger] at
 * [microsPerImage], an upper estimate of a low-quality 1024 × 1024 edit. Failed and refused calls cost nothing.
 */
internal class CharacterForgeService(
    val gateway: ImageGateway,
    val ledger: SpendLedger,
    val microsPerImage: Long = DEFAULT_MICROS_PER_IMAGE,
    val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /** $0.05 per image: above OpenAI's published low-quality price, so the cap is never exceeded. */
        const val DEFAULT_MICROS_PER_IMAGE: Long = 50_000

        /** Largest photo, in bytes, after the device resized it. */
        const val MAX_PHOTO_BYTES: Int = 1024 * 1024
    }
}

/**
 * `POST /api/ai/character` (ADR-0013): `{"consent": true, "styles": ["pixel", …], "photo": base64 PNG}` from a signed-in
 * account, answered with `{"images": [{"style", "label", "data", "provenance"}], "refused": [{"style", "reason"}]}`.
 * The photo goes only to the image model: it is never stored or logged here. Without [forge] (no image key) every
 * request is refused with a plain message.
 */
internal fun Application.characterRoutes(
    accounts: Accounts,
    forge: CharacterForgeService?,
) {
    routing {
        post("/api/ai/character") {
            try {
                val account = call.signedIn(accounts).second
                val service = forge ?: throw ArcadeProblem(HttpStatusCode.ServiceUnavailable, NOT_SET_UP)
                val text =
                    call.readLimitedText(CharacterForgeService.MAX_PHOTO_BYTES * 2) {
                        ArcadeProblem(HttpStatusCode.PayloadTooLarge, "The photo is too large. Choose a smaller one.")
                    }
                val (styles, photo) = parse(text)
                val images = mutableListOf<JsonObject>()
                val refused = mutableListOf<JsonObject>()
                for (style in styles) {
                    val reservation =
                        when (val decision = withContext(Dispatchers.IO) { service.ledger.reserve(account.id, service.microsPerImage) }) {
                            is SpendDecision.Refused -> {
                                if (images.isEmpty() &&
                                    refused.isEmpty()
                                ) {
                                    throw ArcadeProblem(HttpStatusCode.TooManyRequests, decision.message)
                                }
                                refused += refusal(style, decision.message)
                                continue
                            }

                            is SpendDecision.Allowed -> {
                                decision.reservation
                            }
                        }
                    val result =
                        try {
                            service.gateway.character(photo, style)
                        } catch (cancelled: CancellationException) {
                            withContext(Dispatchers.IO) { service.ledger.settle(reservation, 0) }
                            throw cancelled
                        } catch (failure: Exception) {
                            // Only the type: neither the photo nor provider messages are logged.
                            call.application.log.warn("Character image call failed: ${failure::class.simpleName}")
                            null
                        }
                    when (result) {
                        is ImageResult.Image -> {
                            val png =
                                try {
                                    if (style == CharacterStyle.Pixel) PixelArt.pixelate(result.png) else PixelArt.fit(result.png)
                                } catch (_: IllegalArgumentException) {
                                    null
                                }
                            if (png == null) {
                                withContext(Dispatchers.IO) { service.ledger.settle(reservation, 0) }
                                refused += refusal(style, AGAIN)
                                continue
                            }
                            withContext(Dispatchers.IO) { service.ledger.settle(reservation, service.microsPerImage) }
                            val date = Instant.ofEpochMilli(service.clock()).atZone(ZoneOffset.UTC).toLocalDate()
                            images +=
                                JsonObject(
                                    mapOf(
                                        "style" to JsonPrimitive(style.id),
                                        "label" to JsonPrimitive(style.label),
                                        "data" to JsonPrimitive(ProjectImages.encode(png)),
                                        "provenance" to JsonPrimitive("${service.gateway.provenance}, $date"),
                                    ),
                                )
                        }

                        is ImageResult.Refused -> {
                            withContext(Dispatchers.IO) { service.ledger.settle(reservation, 0) }
                            refused += refusal(style, result.reason)
                        }

                        null -> {
                            withContext(Dispatchers.IO) { service.ledger.settle(reservation, 0) }
                            refused += refusal(style, AGAIN)
                        }
                    }
                }
                val body = JsonObject(mapOf("images" to JsonArray(images), "refused" to JsonArray(refused)))
                call.respondText(body.toString(), ContentType.Application.Json)
            } catch (problem: ArcadeProblem) {
                call.respondProblem(problem.status, problem.message ?: "The request failed.")
            }
        }
    }
}

/** The styles and photo of a request, or a 400 problem explaining what is wrong. Consent is required. */
private fun parse(text: String): Pair<List<CharacterStyle>, ByteArray> {
    fun bad(message: String): Nothing = throw ArcadeProblem(HttpStatusCode.BadRequest, message)
    val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: bad("This character request is damaged.")
    if ((root["consent"] as? JsonPrimitive)?.booleanOrNull != true) bad("Confirm that this is you, or that the person agreed.")
    val styles =
        (root["styles"] as? JsonArray ?: bad("Choose at least one style."))
            .map { (it as? JsonPrimitive)?.content?.let(CharacterStyle::of) ?: bad("Choose Pixel art, Cartoon or Chibi.") }
            .distinct()
    if (styles.isEmpty()) bad("Choose at least one style.")
    val encoded = (root["photo"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad("Add a photo.")
    val photo = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: bad("The photo is damaged.")
    if (photo.size > CharacterForgeService.MAX_PHOTO_BYTES) bad("The photo is too large. Choose a smaller one.")
    runCatching { ProjectImages.check(photo) }.getOrNull() ?: bad("Send the photo as a PNG of at most 2048 × 2048 pixels.")
    return styles to photo
}

private fun refusal(
    style: CharacterStyle,
    reason: String,
) = JsonObject(mapOf("style" to JsonPrimitive(style.id), "reason" to JsonPrimitive(reason)))

private const val NOT_SET_UP = "Character Forge is not set up on this server."
private const val AGAIN = "This style could not be made just now. Try again in a moment."
