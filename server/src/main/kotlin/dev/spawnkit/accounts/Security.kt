package dev.spawnkit.accounts

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Argon2id password hashes in the standard `$argon2id$v=19$m=…,t=…,p=…$salt$hash` form (ADR-0010). The stored
 * parameters are used to verify, so the defaults can be raised later without breaking old hashes.
 */
internal class Passwords(
    private val memoryKb: Int = 65_536,
    private val iterations: Int = 3,
    private val random: SecureRandom = SecureRandom(),
) {
    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val hash = derive(password, salt, memoryKb, iterations)
        return "\$argon2id\$v=19\$m=$memoryKb,t=$iterations,p=1\$${b64.encodeToString(salt)}\$${b64.encodeToString(hash)}"
    }

    /** Whether [password] matches [encoded]; malformed hashes never match. */
    fun verify(
        password: String,
        encoded: String,
    ): Boolean {
        val parts = encoded.split('$')
        if (parts.size != 6 || parts[1] != "argon2id" || parts[2] != "v=19") return false
        val params = parts[3].split(',').associate { it.substringBefore('=') to it.substringAfter('=').toIntOrNull() }
        val memory = params["m"] ?: return false
        val time = params["t"] ?: return false
        val (salt, expected) =
            try {
                b64d.decode(parts[4]) to b64d.decode(parts[5])
            } catch (_: IllegalArgumentException) {
                return false
            }
        return MessageDigest.isEqual(derive(password, salt, memory, time), expected)
    }

    private fun derive(
        password: String,
        salt: ByteArray,
        memory: Int,
        time: Int,
    ): ByteArray {
        val params =
            Argon2Parameters
                .Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(time)
                .withMemoryAsKB(memory)
                .withParallelism(1)
                .withSalt(salt)
                .build()
        return ByteArray(HASH_BYTES).also { Argon2BytesGenerator().apply { init(params) }.generateBytes(password.toCharArray(), it) }
    }

    private companion object {
        const val SALT_BYTES = 16
        const val HASH_BYTES = 32
        val b64: Base64.Encoder = Base64.getEncoder().withoutPadding()
        val b64d: Base64.Decoder = Base64.getDecoder()
    }
}

/** Random session tokens and codes, and the SHA-256 hashes the database keeps instead of them. */
internal object Tokens {
    private val random = SecureRandom()

    /** 32 random bytes, Base64url without padding. */
    fun newToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    /** A 6-digit code. */
    fun newCode(): String = random.nextInt(1_000_000).toString().padStart(6, '0')

    /** Lowercase hex SHA-256 of [value]. */
    fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    /** Constant-time comparison of two hashes. */
    fun same(
        a: String,
        b: String,
    ): Boolean = MessageDigest.isEqual(a.encodeToByteArray(), b.encodeToByteArray())
}

/** Counts attempts per key in a sliding window, in memory; a restart forgets them, which only relaxes the limit. */
internal class AttemptLimiter(
    private val limit: Int,
    private val windowMillis: Long,
    private val clock: () -> Long,
) {
    private val attempts = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun blocked(key: String): Boolean = recent(key).size >= limit

    @Synchronized
    fun record(key: String) {
        recent(key).addLast(clock())
    }

    @Synchronized
    fun clear(key: String) {
        attempts.remove(key)
    }

    private fun recent(key: String): ArrayDeque<Long> {
        val now = clock()
        val queue = attempts.getOrPut(key) { ArrayDeque() }
        while (queue.isNotEmpty() && now - queue.first() >= windowMillis) queue.removeFirst()
        return queue
    }
}
