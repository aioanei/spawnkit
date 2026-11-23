package dev.spawnkit

import dev.spawnkit.accounts.MailSender
import dev.spawnkit.hostedai.AiLimits
import dev.spawnkit.hostedai.HostedAi
import java.math.BigDecimal
import java.nio.file.Path

/**
 * The server's settings, read once from environment variables (see `docs/arcade-hosting.md`). Secrets such as the
 * model key and the SMTP password are kept out of [toString], so the configuration can be logged.
 */
internal class ServerConfig private constructor(
    /** Port to listen on (`PORT`). */
    val port: Int,
    /** Address to listen on (`HOST`). */
    val host: String,
    /** Local development mode (`SPAWNKIT_LOCAL=1`): codes may be printed to the console. */
    val local: Boolean,
    /** The SQLite database, `spawnkit.db` in `SPAWNKIT_DATA_DIR`. */
    val databaseFile: Path,
    /** Published game files (`SPAWNKIT_ARCADE_DIR`, default `arcade` in `SPAWNKIT_DATA_DIR`). */
    val arcadeDirectory: Path,
    /** How account emails are sent, or `null` when email sign-in is unavailable. */
    val mail: MailSender?,
    /** Hosted AI spending limits (`SPAWNKIT_AI_MONTHLY_DOLLARS`, `SPAWNKIT_AI_ACCOUNT_DAILY_DOLLARS`). */
    val aiLimits: AiLimits,
    /** Longest model reply, in tokens. */
    val maxOutputTokens: Int,
    private val anthropicKey: String?,
    private val openAiKey: String? = null,
    /** Image model for Character Forge (`SPAWNKIT_IMAGE_MODEL`). */
    val imageModel: String = dev.spawnkit.hostedai.DEFAULT_IMAGE_MODEL,
) {
    /** Whether Character Forge can run: the image model key is set (ADR-0013). */
    val charactersConfigured: Boolean get() = openAiKey != null

    /** The image model key, for the image gateway only. */
    fun openAiKey(): String? = openAiKey

    /** Whether hosted AI can run: the model key is set. */
    val aiConfigured: Boolean get() = anthropicKey != null

    /** The model key, for the model gateway only. */
    fun anthropicKey(): String? = anthropicKey

    override fun toString(): String =
        "ServerConfig(port=$port, host=$host, local=$local, database=$databaseFile, arcade=$arcadeDirectory, " +
            "email=${mail?.let { it::class.simpleName } ?: "unavailable"}, ai=${if (aiConfigured) "configured" else "not configured"}, " +
            "characters=${if (charactersConfigured) "configured" else "not configured"}, " +
            "limits=$aiLimits)"

    companion object {
        /** Reads the settings from [env]. Invalid values stop the server with a message naming the variable. */
        fun fromEnvironment(env: Map<String, String> = System.getenv()): ServerConfig {
            val local = env["SPAWNKIT_LOCAL"]?.trim() == "1"
            val data =
                env["SPAWNKIT_DATA_DIR"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                    ?: Path.of(System.getProperty("user.home"), ".spawnkit")
            val defaults = AiLimits()
            return ServerConfig(
                port = env["PORT"]?.let { requireNotNull(it.trim().toIntOrNull()) { "PORT must be a number" } } ?: DEFAULT_PORT,
                host = env["HOST"]?.takeIf { it.isNotBlank() } ?: "0.0.0.0",
                local = local,
                databaseFile = data.resolve("spawnkit.db"),
                arcadeDirectory = env["SPAWNKIT_ARCADE_DIR"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: data.resolve("arcade"),
                mail = MailSender.fromEnvironment(env, local),
                aiLimits =
                    AiLimits(
                        monthlyMicros = dollars(env, "SPAWNKIT_AI_MONTHLY_DOLLARS") ?: defaults.monthlyMicros,
                        dailyMicros = dollars(env, "SPAWNKIT_AI_ACCOUNT_DAILY_DOLLARS") ?: defaults.dailyMicros,
                    ),
                maxOutputTokens = HostedAi.DEFAULT_MAX_OUTPUT_TOKENS,
                anthropicKey = env["ANTHROPIC_API_KEY"]?.trim()?.ifEmpty { null },
                openAiKey = env["OPENAI_API_KEY"]?.trim()?.ifEmpty { null },
                imageModel = env["SPAWNKIT_IMAGE_MODEL"]?.trim()?.ifEmpty { null } ?: dev.spawnkit.hostedai.DEFAULT_IMAGE_MODEL,
            )
        }

        /** A dollar amount in millionths, or `null` when [name] is not set. */
        private fun dollars(
            env: Map<String, String>,
            name: String,
        ): Long? {
            val text = env[name]?.trim()?.ifEmpty { null } ?: return null
            val amount = text.toBigDecimalOrNull()
            require(amount != null && amount.signum() >= 0) { "$name must be a dollar amount such as 20 or 0.50, not '$text'" }
            return amount.multiply(BigDecimal(1_000_000)).toLong()
        }
    }
}
