package dev.spawnkit.accounts

import dev.spawnkit.data.Db
import java.nio.file.Files
import java.nio.file.Path

/** Captures account emails instead of sending them. */
internal class CapturedMail : MailSender {
    val sent = mutableListOf<Triple<String, String, String>>()

    override fun send(
        to: String,
        subject: String,
        body: String,
    ) {
        sent += Triple(to, subject, body)
    }

    fun lastCode(to: String): String = Regex("\\b\\d{6}\\b").find(sent.last { it.first == to }.third)!!.value
}

/** A fresh database in `build/`, so tests never share state. */
internal fun testDb(): Db =
    Db.open(Files.createTempDirectory(Files.createDirectories(Path.of("build")), "accounts-").resolve("spawnkit.db"))

/** Cheap Argon2id parameters: the real ones take ~100 ms per hash. */
internal fun testPasswords(): Passwords = Passwords(memoryKb = 1024, iterations = 1)
