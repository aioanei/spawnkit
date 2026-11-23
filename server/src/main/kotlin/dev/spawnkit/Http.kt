package dev.spawnkit

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.CharacterCodingException

/** A request refused with [status] and a plain message for the creator. */
internal class ArcadeProblem(
    val status: HttpStatusCode,
    message: String,
) : Exception(message)

/** Answers `{"error": detail}` with [status]. */
internal suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    detail: String,
) {
    val error = JsonObject(mapOf("error" to JsonPrimitive(detail.take(512))))
    respondText(error.toString(), ContentType.Application.Json, status)
}

/** The token from `Authorization: Bearer <token>`, or `null` when the request has none. */
internal fun ApplicationCall.bearerToken(): String? {
    val header = request.headers[HttpHeaders.Authorization] ?: return null
    if (!header.startsWith("Bearer ", ignoreCase = true)) return null
    return header.substring("Bearer ".length).trim().ifEmpty { null }
}

/** Reads a UTF-8 body of at most [maxBytes], refusing larger, slower or non-UTF-8 bodies with [tooLarge]'s problem. */
internal suspend fun ApplicationCall.readLimitedText(
    maxBytes: Int,
    tooLarge: () -> ArcadeProblem,
): String {
    if ((request.contentLength() ?: 0) > maxBytes) throw tooLarge()
    val buffer =
        withTimeoutOrNull(30_000) { receiveChannel().readBuffer(maxBytes.toLong() + 1) }
            ?: throw ArcadeProblem(HttpStatusCode.RequestTimeout, "The upload timed out. Try again.")
    if (buffer.size > maxBytes) throw tooLarge()
    return try {
        buffer.readByteArray().decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        throw ArcadeProblem(HttpStatusCode.BadRequest, "Send the request as UTF-8 text.")
    }
}
