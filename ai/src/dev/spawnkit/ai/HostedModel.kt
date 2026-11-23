package dev.spawnkit.ai

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** SpawnKit's server refused or could not answer a hosted model request; [message] is the server's plain explanation. */
public class HostedModelException(
    message: String,
) : Exception(message)

/**
 * SpawnKit's hosted AI (ADR-0011): each model request goes to `POST {baseUrl}/api/ai/reply`, signed in with the
 * account's session [token]. The server holds the model key, sets the instructions and enforces the spending limits;
 * the agent loop and every tool stay on the device. Refusals, such as a reached limit, become a
 * [HostedModelException] carrying the server's message, which [AgentSession] shows as a failed request.
 */
public class HostedModel(
    private val baseUrl: String,
    private val token: String,
    private val http: HttpClient,
) : AgentModel {
    override val info: ModelInfo =
        ModelInfo(
            name = "SpawnKit AI",
            provider = "SpawnKit",
            live = true,
            label = "Live: SpawnKit AI (free, with limits)",
            inputPricePerMillion = 3.0,
            outputPricePerMillion = 15.0,
        )

    override suspend fun reply(request: ModelRequest): ModelReply {
        val response =
            http.post("${baseUrl.trimEnd('/')}/api/ai/reply") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(ModelRequestJson.encodeRequest(request))
            }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw HostedModelException(errorMessage(body) ?: "SpawnKit AI could not answer (${response.status.value}).")
        }
        return try {
            ModelRequestJson.decodeReply(body)
        } catch (_: IllegalArgumentException) {
            throw HostedModelException("SpawnKit AI sent an answer this version cannot read. Update SpawnKit.")
        }
    }

    private fun errorMessage(body: String): String? =
        try {
            ((Json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonPrimitive)?.content
        } catch (_: Exception) {
            null
        }
}
