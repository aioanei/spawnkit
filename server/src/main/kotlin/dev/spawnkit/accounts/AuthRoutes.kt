package dev.spawnkit.accounts

import dev.spawnkit.ArcadeProblem
import dev.spawnkit.bearerToken
import dev.spawnkit.readLimitedText
import dev.spawnkit.respondProblem
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Largest accepted account request body. */
private const val MAX_AUTH_BYTES = 8 * 1024

/**
 * The account routes (ADR-0010). Requests and answers are small JSON objects; errors are `{"error": message}`.
 *
 * - `POST /api/auth/register`, `/verify`, `/sign-in`, `/sign-out`, `/reset`, `/reset/confirm`
 * - `GET /api/me` and `DELETE /api/me`, signed in with `Authorization: Bearer <token>`
 *
 * Requests that send a code by email are limited per network address, on top of the per-email limits in [Accounts].
 */
internal fun Application.authRoutes(
    accounts: Accounts,
    clock: () -> Long = System::currentTimeMillis,
) {
    val codeRequests = AttemptLimiter(CODE_REQUESTS_PER_ADDRESS, CODE_WINDOW, clock)

    fun ApplicationCall.limitCodeRequests() {
        val from = request.local.remoteAddress
        if (codeRequests.blocked(
                from,
            )
        ) {
            throw ArcadeProblem(HttpStatusCode.TooManyRequests, "Too many code requests. Try again in 15 minutes.")
        }
        codeRequests.record(from)
    }

    routing {
        post("/api/auth/register") {
            call.accountResponse {
                val body = readJson()
                limitCodeRequests()
                val message = io { accounts.register(body.field("email"), body.field("password"), body.field("displayName")) }
                respondMessage(HttpStatusCode.Accepted, message)
            }
        }
        post("/api/auth/verify") {
            call.accountResponse {
                val body = readJson()
                respondAuth(io { accounts.verify(body.field("email"), body.field("code")) })
            }
        }
        post("/api/auth/sign-in") {
            call.accountResponse {
                val body = readJson()
                respondAuth(io { accounts.signIn(body.field("email"), body.field("password"), request.local.remoteAddress) })
            }
        }
        post("/api/auth/sign-out") {
            call.accountResponse {
                val token = signedIn(accounts).first
                io { accounts.signOut(token) }
                respond(HttpStatusCode.NoContent)
            }
        }
        post("/api/auth/reset") {
            call.accountResponse {
                val body = readJson()
                limitCodeRequests()
                respondMessage(HttpStatusCode.Accepted, io { accounts.requestReset(body.field("email")) })
            }
        }
        post("/api/auth/reset/confirm") {
            call.accountResponse {
                val body = readJson()
                respondAuth(io { accounts.reset(body.field("email"), body.field("code"), body.field("password")) })
            }
        }
        get("/api/me") {
            call.accountResponse {
                val account = signedIn(accounts).second
                respondText(account.json().toString(), ContentType.Application.Json)
            }
        }
        delete("/api/me") {
            call.accountResponse {
                val token = signedIn(accounts).first
                io { accounts.delete(token) }
                respond(HttpStatusCode.NoContent)
            }
        }
    }
}

/** The bearer token and its account, or a 401 problem. */
internal suspend fun ApplicationCall.signedIn(accounts: Accounts): Pair<String, AccountInfo> {
    val token = bearerToken() ?: throw notSignedIn()
    val account = withContext(Dispatchers.IO) { accounts.account(token) } ?: throw notSignedIn()
    return token to account
}

/** The account signed in with the request's bearer token; `null` without a token; a 401 problem for a bad one. */
internal suspend fun ApplicationCall.optionalAccount(accounts: Accounts): AccountInfo? {
    if (bearerToken() == null) return null
    return signedIn(accounts).second
}

private fun notSignedIn() = ArcadeProblem(HttpStatusCode.Unauthorized, "Sign in to do this. Your session may have ended.")

internal fun AccountInfo.json(): JsonObject =
    buildJsonObject {
        put("id", JsonPrimitive(id))
        put("displayName", JsonPrimitive(displayName))
        put("email", JsonPrimitive(email))
        put("plan", JsonPrimitive(plan))
    }

private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

private suspend fun ApplicationCall.readJson(): JsonObject {
    val text =
        readLimitedText(MAX_AUTH_BYTES) {
            ArcadeProblem(HttpStatusCode.PayloadTooLarge, "This request is too large.")
        }
    return try {
        Json.parseToJsonElement(text).jsonObject
    } catch (_: SerializationException) {
        throw ArcadeProblem(HttpStatusCode.BadRequest, "Send the request as a JSON object.")
    } catch (_: IllegalArgumentException) {
        throw ArcadeProblem(HttpStatusCode.BadRequest, "Send the request as a JSON object.")
    }
}

private fun JsonObject.field(name: String): String =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?: throw ArcadeProblem(HttpStatusCode.BadRequest, "The request needs \"$name\".")

private suspend fun ApplicationCall.respondMessage(
    status: HttpStatusCode,
    message: String,
) = respondText(buildJsonObject { put("message", JsonPrimitive(message)) }.toString(), ContentType.Application.Json, status)

private suspend fun ApplicationCall.respondAuth(result: AuthResult) {
    when (result) {
        is AuthResult.SignedIn -> {
            // The token is a credential: no cache may keep the answer.
            response.header(HttpHeaders.CacheControl, "no-store")
            val body =
                buildJsonObject {
                    put("token", JsonPrimitive(result.token))
                    put("account", result.account.json())
                }
            respondText(body.toString(), ContentType.Application.Json)
        }

        is AuthResult.Refused -> {
            val status = if (result.retryLater) HttpStatusCode.TooManyRequests else HttpStatusCode.Unauthorized
            respondProblem(status, result.message)
        }
    }
}

private suspend fun RoutingCall.accountResponse(block: suspend RoutingCall.() -> Unit) {
    try {
        block()
    } catch (problem: ArcadeProblem) {
        respondProblem(problem.status, problem.message ?: "The request failed.")
    } catch (invalid: IllegalArgumentException) {
        respondProblem(HttpStatusCode.BadRequest, invalid.message ?: "The request is not valid.")
    } catch (unavailable: EmailUnavailable) {
        respondProblem(HttpStatusCode.ServiceUnavailable, unavailable.message ?: "Email sign-in is unavailable.")
    }
}

private const val CODE_REQUESTS_PER_ADDRESS = 10
private const val CODE_WINDOW = 15 * 60_000L
