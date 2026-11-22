package dev.spawnkit.accounts

import dev.spawnkit.data.Db
import dev.spawnkit.data.EmailCodes
import dev.spawnkit.data.Identities
import dev.spawnkit.data.Sessions
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import dev.spawnkit.data.Accounts as AccountRows

/** What a signed-in client may know about its own account. */
internal data class AccountInfo(
    val id: Long,
    val displayName: String,
    val email: String?,
    val plan: String,
)

/** The outcome of an action that signs in. */
internal sealed interface AuthResult {
    /** Signed in: [token] goes back to the client once and is stored only as a hash. */
    data class SignedIn(
        val token: String,
        val account: AccountInfo,
    ) : AuthResult

    /** Refused with a plain message that never reveals whether an email has an account. */
    data class Refused(
        val message: String,
    ) : AuthResult
}

/**
 * Email accounts (ADR-0010): registration with a 6-digit code, sign-in, password reset, sign-out and deletion.
 *
 * - Answers never reveal whether an email has an account. An email that already has one receives a notice instead
 *   of a code.
 * - Codes and session tokens are stored only as SHA-256 hashes. Codes expire after 15 minutes and allow 5 tries.
 * - Sign-in is limited per email and per network address.
 */
internal class Accounts(
    private val db: Db,
    private val mail: MailSender?,
    private val passwords: Passwords = Passwords(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val perEmail = AttemptLimiter(EMAIL_ATTEMPTS, WINDOW, clock)
    private val perAddress = AttemptLimiter(ADDRESS_ATTEMPTS, WINDOW, clock)

    /** A hash of a random password, verified for unknown emails so both answers take about as long. */
    private val decoy: String by lazy { passwords.hash(Tokens.newToken()) }

    /** Whether email sign-in can send codes on this server. */
    val emailAvailable: Boolean get() = mail != null

    /** Starts an email account, or re-sends the code of one not verified yet. Returns what to tell the creator. */
    fun register(
        email: String,
        password: String,
        displayName: String,
    ): String {
        val address = normalized(email)
        validatePassword(password)
        val name = displayName.trim()
        require(name.length in 1..MAX_NAME) { "Choose a name of 1 to $MAX_NAME characters." }
        val sender = requireMail()
        val existing = db.tx { identity(address) }
        when {
            existing == null -> {
                db.tx {
                    val id =
                        AccountRows.insert {
                            it[AccountRows.displayName] = name
                            it[AccountRows.email] = address
                            it[createdAt] = clock()
                        }[AccountRows.id]
                    Identities.insert {
                        it[account] = id
                        it[provider] = EMAIL
                        it[subject] = address
                        it[passwordHash] = passwords.hash(password)
                    }
                }
                sendCode(sender, address, VERIFY)
            }

            !existing.verified -> {
                db.tx {
                    Identities.update({ Identities.id eq existing.identity }) { it[passwordHash] = passwords.hash(password) }
                    AccountRows.update({ AccountRows.id eq existing.account }) { it[AccountRows.displayName] = name }
                }
                sendCode(sender, address, VERIFY)
            }

            else -> {
                sender.send(
                    address,
                    "Your SpawnKit account",
                    "Someone asked to create a SpawnKit account with this email, but you already have one. " +
                        "Sign in, or reset your password if you forgot it. If this was not you, ignore this email.",
                )
            }
        }
        return "Check your email for a 6-digit code to finish."
    }

    /** Confirms the email with the code from [register] and signs in. */
    fun verify(
        email: String,
        code: String,
    ): AuthResult {
        val address = normalized(email)
        if (!consumeCode(address, VERIFY, code)) return AuthResult.Refused(BAD_CODE)
        val found = db.tx { identity(address) } ?: return AuthResult.Refused(BAD_CODE)
        db.tx { AccountRows.update({ AccountRows.id eq found.account }) { it[verified] = true } }
        return signedIn(found.account)
    }

    /** Signs in with email and password from network address [from]. */
    fun signIn(
        email: String,
        password: String,
        from: String,
    ): AuthResult {
        val address = email.trim().lowercase()
        if (perEmail.blocked(address) || perAddress.blocked(from)) return AuthResult.Refused(TOO_MANY)
        val found = db.tx { identity(address) }
        val matches = passwords.verify(password, found?.passwordHash ?: decoy) && found != null
        if (!matches) {
            perEmail.record(address)
            perAddress.record(from)
            return AuthResult.Refused(WRONG)
        }
        if (!found.verified) return AuthResult.Refused("Verify your email first: enter the code we sent, or register again for a new one.")
        perEmail.clear(address)
        return signedIn(found.account)
    }

    /** The account signed in with [token], or `null` when it is unknown, revoked or expired. */
    fun account(token: String): AccountInfo? =
        db.tx {
            val session =
                Sessions
                    .selectAll()
                    .where { (Sessions.tokenHash eq Tokens.hash(token)) and (Sessions.expiresAt greater clock()) }
                    .singleOrNull() ?: return@tx null
            info(session[Sessions.account])
        }

    /** Revokes [token]. */
    fun signOut(token: String) {
        db.tx { Sessions.deleteWhere { tokenHash eq Tokens.hash(token) } }
    }

    /** Sends a reset code when [email] has an account; the answer is the same either way. */
    fun requestReset(email: String): String {
        val address = normalized(email)
        val sender = requireMail()
        if (db.tx { identity(address) } != null) sendCode(sender, address, RESET)
        return "If that email has a SpawnKit account, we sent it a 6-digit code."
    }

    /** Sets a new password with a reset code, ends every other session and signs in. */
    fun reset(
        email: String,
        code: String,
        newPassword: String,
    ): AuthResult {
        val address = normalized(email)
        validatePassword(newPassword)
        if (!consumeCode(address, RESET, code)) return AuthResult.Refused(BAD_CODE)
        val found = db.tx { identity(address) } ?: return AuthResult.Refused(BAD_CODE)
        db.tx {
            Identities.update({ Identities.id eq found.identity }) { it[passwordHash] = passwords.hash(newPassword) }
            // A reset code proves the email, so the account counts as verified.
            AccountRows.update({ AccountRows.id eq found.account }) { it[verified] = true }
            Sessions.deleteWhere { account eq found.account }
        }
        perEmail.clear(address)
        return signedIn(found.account)
    }

    /** Deletes the account signed in with [token] and everything it owns, except published games, which stay unowned. */
    fun delete(token: String): Boolean {
        val account = account(token) ?: return false
        db.tx {
            account.email?.let { address -> EmailCodes.deleteWhere { email eq address } }
            AccountRows.deleteWhere { id eq account.id }
        }
        return true
    }

    private fun signedIn(account: Long): AuthResult {
        val token = Tokens.newToken()
        val info =
            db.tx {
                Sessions.insert {
                    it[tokenHash] = Tokens.hash(token)
                    it[Sessions.account] = account
                    it[createdAt] = clock()
                    it[expiresAt] = clock() + SESSION_MILLIS
                }
                info(account)
            } ?: return AuthResult.Refused(WRONG)
        return AuthResult.SignedIn(token, info)
    }

    private fun sendCode(
        sender: MailSender,
        address: String,
        purpose: String,
    ) {
        val code = Tokens.newCode()
        db.tx {
            EmailCodes.deleteWhere { (email eq address) and (EmailCodes.purpose eq purpose) }
            EmailCodes.insert {
                it[email] = address
                it[EmailCodes.purpose] = purpose
                it[codeHash] = Tokens.hash(code)
                it[expiresAt] = clock() + CODE_MILLIS
            }
        }
        val what = if (purpose == VERIFY) "finish creating your SpawnKit account" else "reset your SpawnKit password"
        sender.send(address, "Your SpawnKit code: $code", "Enter $code to $what. The code works for 15 minutes.")
    }

    private fun consumeCode(
        address: String,
        purpose: String,
        code: String,
    ): Boolean =
        db.tx {
            val row =
                EmailCodes
                    .selectAll()
                    .where { (EmailCodes.email eq address) and (EmailCodes.purpose eq purpose) }
                    .singleOrNull() ?: return@tx false
            val id = row[EmailCodes.id]
            if (row[EmailCodes.expiresAt] <= clock() || row[EmailCodes.tries] >= MAX_TRIES) return@tx false
            if (!Tokens.same(row[EmailCodes.codeHash], Tokens.hash(code.trim()))) {
                EmailCodes.update({ EmailCodes.id eq id }) { it[tries] = row[EmailCodes.tries] + 1 }
                return@tx false
            }
            EmailCodes.deleteWhere { EmailCodes.id eq id }
            true
        }

    private class Found(
        val identity: Long,
        val account: Long,
        val passwordHash: String?,
        val verified: Boolean,
    )

    private fun JdbcTransaction.identity(address: String): Found? {
        val row =
            Identities
                .selectAll()
                .where { (Identities.provider eq EMAIL) and (Identities.subject eq address) }
                .singleOrNull() ?: return null
        val account = row[Identities.account]
        val verified =
            AccountRows
                .selectAll()
                .where { AccountRows.id eq account }
                .single()[AccountRows.verified]
        return Found(row[Identities.id], account, row[Identities.passwordHash], verified)
    }

    private fun JdbcTransaction.info(account: Long): AccountInfo? =
        AccountRows.selectAll().where { AccountRows.id eq account }.singleOrNull()?.let {
            AccountInfo(it[AccountRows.id], it[AccountRows.displayName], it[AccountRows.email], it[AccountRows.plan])
        }

    private fun requireMail(): MailSender = mail ?: throw IllegalStateException("Email sign-in is not set up on this server.")

    private fun normalized(email: String): String {
        val address = email.trim().lowercase()
        require(address.length <= MAX_EMAIL && EMAIL_FORMAT.matches(address)) { "Enter a valid email address." }
        return address
    }

    private fun validatePassword(password: String) {
        require(password.length in MIN_PASSWORD..MAX_PASSWORD) { "Choose a password of $MIN_PASSWORD to $MAX_PASSWORD characters." }
    }

    private companion object {
        const val EMAIL = "email"
        const val VERIFY = "verify"
        const val RESET = "reset"
        const val MAX_NAME = 60
        const val MAX_EMAIL = 254
        const val MIN_PASSWORD = 10
        const val MAX_PASSWORD = 128
        const val MAX_TRIES = 5
        const val EMAIL_ATTEMPTS = 5
        const val ADDRESS_ATTEMPTS = 10
        const val WINDOW = 15 * 60_000L
        const val CODE_MILLIS = 15 * 60_000L
        const val SESSION_MILLIS = 30L * 24 * 60 * 60_000L
        const val WRONG = "Email or password is not right."
        const val BAD_CODE = "That code is not right or has expired. Ask for a new one."
        const val TOO_MANY = "Too many sign-in attempts. Try again in 15 minutes."
        val EMAIL_FORMAT = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")
    }
}
