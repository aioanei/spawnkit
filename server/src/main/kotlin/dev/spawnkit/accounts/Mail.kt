package dev.spawnkit.accounts

import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties

/** Sends account emails: verification and reset codes, and notices. */
internal interface MailSender {
    fun send(
        to: String,
        subject: String,
        body: String,
    )

    companion object {
        /**
         * SMTP when `SPAWNKIT_SMTP_HOST` is set. Otherwise the console, but only in local mode (`SPAWNKIT_LOCAL=1`), so
         * codes are never written to a production log. `null` means email sign-in is unavailable.
         */
        fun fromEnvironment(
            env: Map<String, String>,
            local: Boolean,
        ): MailSender? {
            val host = env["SPAWNKIT_SMTP_HOST"]?.trim().orEmpty()
            return when {
                host.isNotEmpty() -> {
                    SmtpMail(
                        host = host,
                        port = env["SPAWNKIT_SMTP_PORT"]?.toIntOrNull() ?: DEFAULT_PORT,
                        user = env["SPAWNKIT_SMTP_USER"]?.ifBlank { null },
                        password = env["SPAWNKIT_SMTP_PASSWORD"]?.ifBlank { null },
                        sender = env["SPAWNKIT_SMTP_FROM"]?.ifBlank { null } ?: "SpawnKit <no-reply@$host>",
                    )
                }

                local -> {
                    ConsoleMail()
                }

                else -> {
                    null
                }
            }
        }

        private const val DEFAULT_PORT = 587
    }
}

/** Local mode only: prints emails to the console, clearly marked as not sent. */
internal class ConsoleMail(
    private val out: (String) -> Unit = ::println,
) : MailSender {
    override fun send(
        to: String,
        subject: String,
        body: String,
    ) {
        out("[SpawnKit local mode: email NOT sent] To: $to | $subject | $body")
    }
}

/** SMTP with STARTTLS through Jakarta Mail (Angus Mail). The password comes only from the environment. */
internal class SmtpMail(
    private val host: String,
    private val port: Int,
    private val user: String?,
    private val password: String?,
    private val sender: String,
) : MailSender {
    override fun send(
        to: String,
        subject: String,
        body: String,
    ) {
        val props =
            Properties().apply {
                put("mail.smtp.host", host)
                put("mail.smtp.port", port.toString())
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
                put("mail.smtp.auth", (user != null).toString())
                put("mail.smtp.connectiontimeout", "10000")
                put("mail.smtp.timeout", "10000")
            }
        val session =
            if (user != null) {
                Session.getInstance(
                    props,
                    object : Authenticator() {
                        override fun getPasswordAuthentication() = PasswordAuthentication(user, password.orEmpty())
                    },
                )
            } else {
                Session.getInstance(props)
            }
        // No apply {} here: MimeMessage has its own sender and subject properties, which would shadow the parameters.
        val message = MimeMessage(session)
        message.setFrom(InternetAddress(sender))
        message.setRecipient(Message.RecipientType.TO, InternetAddress(to))
        message.setSubject(subject, "UTF-8")
        message.setText(body, "UTF-8")
        Transport.send(message)
    }
}
