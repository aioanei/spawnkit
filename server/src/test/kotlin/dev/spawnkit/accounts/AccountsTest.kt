package dev.spawnkit.accounts

import dev.spawnkit.data.Games
import dev.spawnkit.data.Identities
import dev.spawnkit.data.Sessions
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Email accounts (C11a, ADR-0010): codes, sessions, limits and deletion, with captured mail and a test clock. */
class AccountsTest {
    private val db = testDb()
    private val mail = CapturedMail()
    private var now = 1_000_000L
    private val accounts = Accounts(db, mail, testPasswords(), clock = { now })

    private fun signedUp(
        email: String = "ada@example.com",
        password: String = "correct horse",
    ): AuthResult.SignedIn {
        accounts.register(email, password, "Ada")
        return assertIs(accounts.verify(email, mail.lastCode(email)))
    }

    @Test
    fun registerVerifyAndSignIn() {
        val notice = accounts.register("Ada@Example.com ", "correct horse", "Ada")
        assertContains(notice, "code")
        val verified = assertIs<AuthResult.SignedIn>(accounts.verify("ada@example.com", mail.lastCode("ada@example.com")))
        assertEquals("Ada", verified.account.displayName)

        val signedIn = assertIs<AuthResult.SignedIn>(accounts.signIn("ADA@example.com", "correct horse", "10.0.0.1"))

        assertEquals(verified.account, accounts.account(signedIn.token))
    }

    @Test
    fun wrongPasswordsAreLimited() {
        signedUp()

        repeat(5) { assertIs<AuthResult.Refused>(accounts.signIn("ada@example.com", "wrong password", "10.0.0.$it")) }
        val blocked = assertIs<AuthResult.Refused>(accounts.signIn("ada@example.com", "correct horse", "10.0.0.9"))

        assertContains(blocked.message, "Too many")
        now += 16 * 60_000L
        assertIs<AuthResult.SignedIn>(accounts.signIn("ada@example.com", "correct horse", "10.0.0.9"))
    }

    @Test
    fun answersDoNotRevealWhetherAnEmailExists() {
        signedUp()

        assertEquals(
            accounts.register("new@example.com", "another pass", "New"),
            accounts.register("ada@example.com", "another pass", "Ada"),
        )
        assertEquals(accounts.requestReset("nobody@example.com"), accounts.requestReset("ada@example.com"))
        assertEquals(
            accounts.signIn("nobody@example.com", "whatever pass", "10.0.0.1"),
            accounts.signIn("ada@example.com", "whatever pass", "10.0.0.2"),
        )
        assertContains(mail.sent.last { it.first == "ada@example.com" && "already" in it.third }.third, "already")
    }

    @Test
    fun codesExpireAndAllowFiveTries() {
        accounts.register("ada@example.com", "correct horse", "Ada")
        val code = mail.lastCode("ada@example.com")

        repeat(5) { assertIs<AuthResult.Refused>(accounts.verify("ada@example.com", "000000")) }

        assertIs<AuthResult.Refused>(accounts.verify("ada@example.com", code), "a sixth try is refused even with the right code")
        accounts.register("ada@example.com", "correct horse", "Ada")
        val fresh = mail.lastCode("ada@example.com")
        now += 16 * 60_000L
        assertIs<AuthResult.Refused>(accounts.verify("ada@example.com", fresh), "codes expire after 15 minutes")
    }

    @Test
    fun tokensAreStoredHashedAndExpire() {
        val token = signedUp().token

        val stored = db.tx { Sessions.selectAll().map { it[Sessions.tokenHash] } }

        assertFalse(token in stored)
        assertEquals(listOf(Tokens.hash(token)), stored)
        now += 31L * 24 * 60 * 60_000L
        assertNull(accounts.account(token))
    }

    @Test
    fun signOutRevokes() {
        val token = signedUp().token

        accounts.signOut(token)

        assertNull(accounts.account(token))
    }

    @Test
    fun resetChangesThePasswordAndEndsOtherSessions() {
        val old = signedUp().token
        accounts.requestReset("ada@example.com")

        val reset = assertIs<AuthResult.SignedIn>(accounts.reset("ada@example.com", mail.lastCode("ada@example.com"), "brand new pass"))

        assertNull(accounts.account(old))
        assertNotNull(accounts.account(reset.token))
        assertIs<AuthResult.Refused>(accounts.signIn("ada@example.com", "correct horse", "10.0.0.1"))
        assertIs<AuthResult.SignedIn>(accounts.signIn("ada@example.com", "brand new pass", "10.0.0.1"))
    }

    @Test
    fun deleteRemovesTheAccountAndFreesItsGames() {
        val signedIn = signedUp()
        db.tx {
            Games.insert {
                it[slug] = "00000000-0000-0000-0000-000000000001"
                it[title] = "Mine"
                it[owner] = signedIn.account.id
                it[publishedAt] = now
                it[updatedAt] = now
            }
        }

        assertTrue(accounts.delete(signedIn.token))

        assertNull(accounts.account(signedIn.token))
        assertEquals(0L, db.tx { Identities.selectAll().where { Identities.account eq signedIn.account.id }.count() })
        assertEquals(listOf<Long?>(null), db.tx { Games.selectAll().map { it[Games.owner] } }, "the game stays published, unowned")
        assertIs<AuthResult.Refused>(accounts.signIn("ada@example.com", "correct horse", "10.0.0.1"))
    }

    @Test
    fun emailsToOneAddressAreLimitedWithoutChangingTheAnswer() {
        val answers = (1..7).map { accounts.register("ada@example.com", "correct horse", "Ada") }

        assertEquals(1, answers.toSet().size, "the answer never changes, so the limit reveals nothing")
        assertEquals(5, mail.sent.count { it.first == "ada@example.com" })
        now += 16 * 60_000L
        accounts.requestReset("ada@example.com")
        assertEquals(6, mail.sent.count { it.first == "ada@example.com" })
    }

    @Test
    fun consoleMailOnlyInLocalMode() {
        assertNull(MailSender.fromEnvironment(emptyMap(), local = false), "no mail means email sign-in is unavailable")
        assertIs<ConsoleMail>(MailSender.fromEnvironment(emptyMap(), local = true))
        assertIs<SmtpMail>(MailSender.fromEnvironment(mapOf("SPAWNKIT_SMTP_HOST" to "smtp.example.com"), local = true))
    }

    @Test
    fun passwordsAreArgon2idAndVerify() {
        val passwords = Passwords(memoryKb = 1024, iterations = 1)
        val hash = passwords.hash("correct horse")

        assertTrue(hash.startsWith("\$argon2id\$v=19\$m=1024,t=1,p=1\$"), hash)
        assertTrue(passwords.verify("correct horse", hash))
        assertFalse(passwords.verify("correct horsf", hash))
    }
}
