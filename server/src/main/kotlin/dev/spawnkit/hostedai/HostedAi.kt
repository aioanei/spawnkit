package dev.spawnkit.hostedai

import dev.spawnkit.ArcadeProblem
import dev.spawnkit.accounts.Accounts
import dev.spawnkit.accounts.signedIn
import dev.spawnkit.ai.AgentSession
import dev.spawnkit.ai.AgentToolbox
import dev.spawnkit.ai.KoogModel
import dev.spawnkit.ai.ModelReply
import dev.spawnkit.ai.ModelRequest
import dev.spawnkit.ai.ModelRequestJson
import dev.spawnkit.editing.ToolDefinition
import dev.spawnkit.gameplay.StandardEngine
import dev.spawnkit.readLimitedText
import dev.spawnkit.respondProblem
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** Asks the real model. The server's key stays inside the implementation. */
internal fun interface ModelGateway {
    suspend fun reply(request: ModelRequest): ModelReply
}

/** The assistant model through Koog, with the owner's key from the server environment and capped replies. */
internal class KoogGateway(
    apiKey: String,
    maxOutputTokens: Int,
    modelId: String = KoogModel.DEFAULT_ANTHROPIC_MODEL,
) : ModelGateway {
    private val model = KoogModel.anthropic(apiKey, modelId, maxOutputTokens = maxOutputTokens)

    override suspend fun reply(request: ModelRequest): ModelReply = model.reply(request)
}

/**
 * The hosted model proxy (ADR-0011). It serves only the SpawnKit assistant: every request gets the server's own
 * instructions and the server's own copies of the toolbox tools, unknown tools are refused, and requests and replies
 * are bounded. [gateway] must cap replies at [maxOutputTokens], which the reservation counts on.
 */
internal class HostedAi(
    val gateway: ModelGateway,
    val ledger: SpendLedger,
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    val maxRequestBytes: Int = MAX_REQUEST_BYTES,
    val prices: ModelPrices = ModelPrices(),
    tools: List<ToolDefinition> = AgentToolbox.definitions(StandardEngine()),
) {
    private val tools = tools.associateBy { it.name }

    /** [request] as it goes to the model: the server's instructions and tool definitions. Unknown tools are refused. */
    fun forwarded(request: ModelRequest): ModelRequest {
        val unknown = request.tools.map { it.name }.filter { it !in tools }
        if (unknown.isNotEmpty()) {
            throw ArcadeProblem(HttpStatusCode.BadRequest, "SpawnKit AI cannot use the tool '${unknown.first().take(64)}'.")
        }
        return request.copy(system = AgentSession.SYSTEM, tools = request.tools.map { tools.getValue(it.name) }.distinctBy { it.name })
    }

    /**
     * The most [request] can cost: every UTF-8 byte counted as an input token (a token is never shorter than a byte)
     * and a full reply.
     */
    fun costBound(request: ModelRequest): Long =
        ModelRequestJson.encodeRequest(request).encodeToByteArray().size * prices.inputMicrosPerToken +
            maxOutputTokens * prices.outputMicrosPerToken

    companion object {
        /** Largest accepted request body: a long session with every tool definition fits. */
        const val MAX_REQUEST_BYTES: Int = 512 * 1024

        /** Longest reply, in tokens. */
        const val DEFAULT_MAX_OUTPUT_TOKENS: Int = KoogModel.DEFAULT_MAX_OUTPUT_TOKENS
    }
}

/**
 * `POST /api/ai/reply` (ADR-0011): a signed-in account's model request, in [ModelRequestJson], answered with the
 * model's reply. Without [hosted] (no model key on this server) every request is refused with a plain message.
 */
internal fun Application.aiRoutes(
    accounts: Accounts,
    hosted: HostedAi?,
) {
    routing {
        post("/api/ai/reply") {
            call.aiResponse {
                val account = signedIn(accounts).second
                val ai = hosted ?: throw ArcadeProblem(HttpStatusCode.ServiceUnavailable, NOT_SET_UP)
                val text =
                    readLimitedText(ai.maxRequestBytes) {
                        ArcadeProblem(HttpStatusCode.PayloadTooLarge, "This conversation is too long for SpawnKit AI. Start a new request.")
                    }
                val request =
                    try {
                        ai.forwarded(ModelRequestJson.decodeRequest(text))
                    } catch (_: IllegalArgumentException) {
                        throw ArcadeProblem(HttpStatusCode.BadRequest, "This AI request is damaged. Update SpawnKit and try again.")
                    }
                val reservation =
                    when (val decision = withContext(Dispatchers.IO) { ai.ledger.reserve(account.id, ai.costBound(request)) }) {
                        is SpendDecision.Refused -> throw ArcadeProblem(HttpStatusCode.TooManyRequests, decision.message)
                        is SpendDecision.Allowed -> decision.reservation
                    }
                val reply =
                    try {
                        ai.gateway.reply(request)
                    } catch (cancelled: CancellationException) {
                        withContext(Dispatchers.IO) { ai.ledger.settle(reservation, 0) }
                        throw cancelled
                    } catch (failure: Exception) {
                        withContext(Dispatchers.IO) { ai.ledger.settle(reservation, 0) }
                        // Only the type: provider messages are not ours to log or show.
                        application.log.warn("Hosted AI call failed: ${failure::class.simpleName}")
                        throw ArcadeProblem(HttpStatusCode.BadGateway, "SpawnKit AI could not answer just now. Try again in a moment.")
                    }
                withContext(Dispatchers.IO) { ai.ledger.settle(reservation, ai.prices.cost(reply.usage)) }
                respondText(ModelRequestJson.encodeReply(reply), ContentType.Application.Json)
            }
        }
    }
}

private const val NOT_SET_UP = "SpawnKit AI is not set up on this server. You can keep building by hand."

private suspend fun RoutingCall.aiResponse(block: suspend RoutingCall.() -> Unit) {
    try {
        block()
    } catch (problem: ArcadeProblem) {
        respondProblem(problem.status, problem.message ?: "The request failed.")
    }
}
