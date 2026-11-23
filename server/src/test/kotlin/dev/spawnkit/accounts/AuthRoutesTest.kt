package dev.spawnkit.accounts

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The account routes (C11a): JSON in and out, bearer tokens, plain refusals. */
class AuthRoutesTest {
    private val mail = CapturedMail()
    private val accounts = Accounts(testDb(), mail, testPasswords())

    private fun routes(
        accounts: Accounts = this.accounts,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application { authRoutes(accounts) }
        block()
    }

    @Test
    fun registerVerifySignInAndReadMe() =
        routes {
            val registered =
                client.postJson(
                    "/api/auth/register",
                    """{"email":"ada@example.com","password":"correct horse","displayName":"Ada"}""",
                )
            assertEquals(HttpStatusCode.Accepted, registered.status)
            assertContains(registered.json().text("message"), "code")

            val verified =
                client.postJson(
                    "/api/auth/verify",
                    """{"email":"ada@example.com","code":"${mail.lastCode("ada@example.com")}"}""",
                )
            assertEquals(HttpStatusCode.OK, verified.status)
            assertEquals("no-store", verified.headers[HttpHeaders.CacheControl])
            assertEquals(
                "Ada",
                verified
                    .json()
                    .getValue("account")
                    .jsonObject
                    .text("displayName"),
            )

            val signedIn = client.postJson("/api/auth/sign-in", """{"email":"ada@example.com","password":"correct horse"}""")
            assertEquals(HttpStatusCode.OK, signedIn.status)
            val me = client.get("/api/me") { bearerAuth(signedIn.json().text("token")) }
            assertEquals(HttpStatusCode.OK, me.status)
            assertEquals("ada@example.com", me.json().text("email"))
            assertFalse("token" in me.bodyAsText(), "account answers never echo a token")
        }

    @Test
    fun signedOutRequestsGet401() =
        routes {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me") { bearerAuth("not-a-token") }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/me").status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/auth/sign-out").status)
            val wrong = client.postJson("/api/auth/sign-in", """{"email":"ada@example.com","password":"wrong password"}""")
            assertEquals(HttpStatusCode.Unauthorized, wrong.status)
            assertContains(wrong.json().text("error"), "not right")
        }

    @Test
    fun signOutAndDeleteEndTheAccount() =
        routes {
            val token = client.signUp()
            assertEquals(HttpStatusCode.NoContent, client.post("/api/auth/sign-out") { bearerAuth(token) }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me") { bearerAuth(token) }.status)

            val again =
                client
                    .postJson(
                        "/api/auth/sign-in",
                        """{"email":"ada@example.com","password":"correct horse"}""",
                    ).json()
                    .text("token")
            assertEquals(HttpStatusCode.NoContent, client.delete("/api/me") { bearerAuth(again) }.status)
            val gone = client.postJson("/api/auth/sign-in", """{"email":"ada@example.com","password":"correct horse"}""")
            assertEquals(HttpStatusCode.Unauthorized, gone.status)
        }

    @Test
    fun resetSetsANewPassword() =
        routes {
            client.signUp()
            assertEquals(HttpStatusCode.Accepted, client.postJson("/api/auth/reset", """{"email":"ada@example.com"}""").status)
            val code = mail.lastCode("ada@example.com")
            val confirmed =
                client.postJson(
                    "/api/auth/reset/confirm",
                    """{"email":"ada@example.com","code":"$code","password":"brand new pass"}""",
                )
            assertEquals(HttpStatusCode.OK, confirmed.status)
            val signedIn = client.postJson("/api/auth/sign-in", """{"email":"ada@example.com","password":"brand new pass"}""")
            assertEquals(HttpStatusCode.OK, signedIn.status)
        }

    @Test
    fun badInputGets400WithAPlainMessage() =
        routes {
            val badEmail = client.postJson("/api/auth/register", """{"email":"nope","password":"correct horse","displayName":"Ada"}""")
            assertEquals(HttpStatusCode.BadRequest, badEmail.status)
            assertContains(badEmail.json().text("error"), "valid email")
            assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/auth/register", "{not json").status)
            assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/auth/register", """{"email":"ada@example.com"}""").status)
            val huge = client.postJson("/api/auth/sign-in", """{"email":"${"a".repeat(20_000)}","password":"x"}""")
            assertEquals(HttpStatusCode.PayloadTooLarge, huge.status)
        }

    @Test
    fun repeatedSignInFailuresGet429() =
        routes {
            client.signUp()
            repeat(5) { client.postJson("/api/auth/sign-in", """{"email":"ada@example.com","password":"wrong password"}""") }
            val blocked = client.postJson("/api/auth/sign-in", """{"email":"ada@example.com","password":"correct horse"}""")
            assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
            assertContains(blocked.json().text("error"), "Too many")
        }

    @Test
    fun codeRequestsFromOneAddressAreLimited() =
        routes {
            val statuses =
                (1..11).map {
                    client.postJson("/api/auth/reset", """{"email":"user$it@example.com"}""").status
                }
            assertEquals(List(10) { HttpStatusCode.Accepted } + HttpStatusCode.TooManyRequests, statuses)
        }

    @Test
    fun withoutMailEmailSignInIsUnavailable() =
        routes(accounts = Accounts(testDb(), mail = null, passwords = testPasswords())) {
            val registered =
                client.postJson(
                    "/api/auth/register",
                    """{"email":"ada@example.com","password":"correct horse","displayName":"Ada"}""",
                )
            assertEquals(HttpStatusCode.ServiceUnavailable, registered.status)
            assertContains(registered.json().text("error"), "not set up")
        }

    private suspend fun HttpClient.signUp(): String {
        postJson("/api/auth/register", """{"email":"ada@example.com","password":"correct horse","displayName":"Ada"}""")
        return postJson(
            "/api/auth/verify",
            """{"email":"ada@example.com","code":"${mail.lastCode("ada@example.com")}"}""",
        ).json().text("token")
    }
}

internal suspend fun HttpClient.postJson(
    path: String,
    body: String,
) = post(path) {
    contentType(ContentType.Application.Json)
    setBody(body)
}

internal suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

internal fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
