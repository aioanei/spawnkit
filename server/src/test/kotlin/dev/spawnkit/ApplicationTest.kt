package dev.spawnkit

import dev.spawnkit.accounts.ConsoleMail
import dev.spawnkit.accounts.SmtpMail
import dev.spawnkit.accounts.json
import dev.spawnkit.accounts.postJson
import dev.spawnkit.accounts.text
import dev.spawnkit.hostedai.AiLimits
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The whole server as `main` starts it, configured from environment variables (C11a). */
class ApplicationTest {
    private val data: Path = Files.createTempDirectory(Files.createDirectories(Path.of("build")), "server-")

    @Test
    fun healthEndpointReportsOk() =
        testApplication {
            application { spawnkit(ServerConfig.fromEnvironment(mapOf("SPAWNKIT_DATA_DIR" to data.toString()))) }
            val response = client.get("/health")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("ok", response.bodyAsText())
        }

    @Test
    fun configComesFromTheEnvironment() {
        val config =
            ServerConfig.fromEnvironment(
                mapOf(
                    "PORT" to "9000",
                    "SPAWNKIT_DATA_DIR" to data.toString(),
                    "SPAWNKIT_LOCAL" to "1",
                    "ANTHROPIC_API_KEY" to "sk-test-not-real",
                    "SPAWNKIT_AI_MONTHLY_DOLLARS" to "12.50",
                    "SPAWNKIT_AI_ACCOUNT_DAILY_DOLLARS" to "0.25",
                ),
            )

        assertEquals(9000, config.port)
        assertEquals(data.resolve("spawnkit.db"), config.databaseFile)
        assertEquals(data.resolve("arcade"), config.arcadeDirectory)
        assertIs<ConsoleMail>(config.mail)
        assertEquals(AiLimits(monthlyMicros = 12_500_000, dailyMicros = 250_000), config.aiLimits)
        assertTrue(config.aiConfigured)
        assertFalse("sk-test" in config.toString(), "the key never appears in logs")
    }

    @Test
    fun defaultsAreSafe() {
        val config = ServerConfig.fromEnvironment(mapOf("SPAWNKIT_DATA_DIR" to data.toString()))

        assertEquals(DEFAULT_PORT, config.port)
        assertNull(config.mail, "no console codes outside local mode")
        assertEquals(AiLimits(), config.aiLimits)
        assertFalse(config.aiConfigured)
        assertIs<SmtpMail>(
            ServerConfig
                .fromEnvironment(
                    mapOf(
                        "SPAWNKIT_DATA_DIR" to data.toString(),
                        "SPAWNKIT_SMTP_HOST" to "smtp.example.com",
                    ),
                ).mail,
        )
    }

    @Test
    fun badLimitsAreRefusedAtStartup() {
        val problem =
            assertFailsWith<IllegalArgumentException> {
                ServerConfig.fromEnvironment(mapOf("SPAWNKIT_DATA_DIR" to data.toString(), "SPAWNKIT_AI_MONTHLY_DOLLARS" to "lots"))
            }
        assertContains(problem.message.orEmpty(), "SPAWNKIT_AI_MONTHLY_DOLLARS")
    }

    @Test
    fun theWholeServerHasAccountsAndSaysWhenAiIsNotSetUp() =
        testApplication {
            application { spawnkit(ServerConfig.fromEnvironment(mapOf("SPAWNKIT_DATA_DIR" to data.toString(), "SPAWNKIT_LOCAL" to "1"))) }
            val registered =
                client.postJson(
                    "/api/auth/register",
                    """{"email":"ada@example.com","password":"correct horse","displayName":"Ada"}""",
                )
            assertEquals(HttpStatusCode.Accepted, registered.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me/games").status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/ai/reply").status)
            assertEquals("[]", client.get("/api/games").bodyAsText())
            assertContains(client.get("/api/server").json().text("ai"), "not set up")
        }
}
