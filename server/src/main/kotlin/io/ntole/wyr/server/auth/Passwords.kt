package io.ntole.wyr.server.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Passwords as the server keeps them (CLAUDE.md §8b, *Accounts*): PBKDF2 with HMAC-SHA256, from the
 * JDK, over a random salt of [SALT_BYTES] for each password. Never the password itself, and neither
 * it nor its hash is ever logged.
 *
 * A stored hash names how it was made, `pbkdf2-sha256$<iterations>$<salt>$<hash>`, the salt and the
 * hash in unpadded Base64, and [verify] reads the count from it. So [ITERATIONS] can be raised later
 * and every hash stored before still verifies at its own count; nothing rehashes one at a new count yet.
 */
object Passwords {
    /**
     * The cost of one hash. Measured on 2026-09-25 on JDK 21 (Zulu, which the Docker image runs) on an
     * Apple M4 Pro: about 9 ms once the JVM is warm, and 80 ms for a JVM's first. Render's free instance
     * has a tenth of a CPU, so there a hash takes roughly ten to twenty times as long, 0.1 to 0.2 s,
     * well under half a second; 210,000 measured 19 ms here, which would have come close to it there.
     */
    const val ITERATIONS: Int = 100_000

    /**
     * A hash no password is known to match, made once, on first use, from random bytes nobody keeps. A
     * login naming no account is checked against it, so it takes as long as one with a wrong password.
     */
    val UNMATCHABLE: String by lazy { hash(opaque(SALT_BYTES)) }

    /** [password] hashed with a fresh salt, in the stored form. */
    fun hash(password: String): String = hash(password, ITERATIONS)

    /** [hash] at [iterations], for a test to make one at another cost, as an older build's would be. */
    internal fun hash(
        password: String,
        iterations: Int,
    ): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val derived = derive(password, salt, iterations)
        return listOf(ALGORITHM, iterations.toString(), base64.encodeToString(salt), base64.encodeToString(derived))
            .joinToString(SEPARATOR)
    }

    /**
     * Whether [password] is the one [stored] was made from, at the count [stored] names, compared in
     * time that does not depend on where the two differ.
     *
     * A [stored] that is not in the form [hash] writes is no hash this server made, and fails loudly
     * rather than reading as a wrong password. Its message never holds it.
     */
    fun verify(
        password: String,
        stored: String,
    ): Boolean {
        val parts = stored.split(SEPARATOR)
        val iterations = parts.getOrNull(1)?.toIntOrNull()
        val salt = parts.getOrNull(2)?.let(::decoded)
        val expected = parts.getOrNull(3)?.let(::decoded)
        check(parts.size == 4 && parts[0] == ALGORITHM && iterations != null && iterations > 0) {
            "a stored password hash is not in the form this server writes"
        }
        check(salt != null && expected != null && expected.size == HASH_BYTES) {
            "a stored password hash is not in the form this server writes"
        }
        return MessageDigest.isEqual(expected, derive(password, salt, iterations))
    }

    private fun derive(
        password: String,
        salt: ByteArray,
        iterations: Int,
    ): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BYTES * Byte.SIZE_BITS)
        try {
            return SecretKeyFactory.getInstance(JDK_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun decoded(part: String): ByteArray? =
        try {
            base64Decoder.decode(part)
        } catch (notBase64: IllegalArgumentException) {
            null
        }

    private fun opaque(bytes: Int): String = base64.encodeToString(ByteArray(bytes).also(random::nextBytes))

    /** The name a stored hash starts with. */
    private const val ALGORITHM = "pbkdf2-sha256"
    private const val JDK_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val SEPARATOR = "$"
    private const val SALT_BYTES = 16

    /** SHA-256's output, so PBKDF2 runs its costly loop once. */
    private const val HASH_BYTES = 32

    private val random = SecureRandom()
    private val base64 = Base64.getEncoder().withoutPadding()
    private val base64Decoder = Base64.getDecoder()
}
