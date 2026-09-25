package io.ntole.wyr.server.auth

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** How the server keeps a password (CLAUDE.md §8b, *Accounts*), at the real cost. */
class PasswordsTest {
    @Test
    fun `a hash verifies its own password and no other`() {
        val stored = Passwords.hash(PASSWORD)

        assertTrue(Passwords.verify(PASSWORD, stored))
        listOf(
            "another" to "correct horse battery stapled",
            "in another case" to PASSWORD.uppercase(),
            "cut short" to PASSWORD.dropLast(1),
            "padded" to " $PASSWORD",
        ).forEach { (case, presented) -> assertFalse(Passwords.verify(presented, stored), case) }
    }

    @Test
    fun `every hash has a salt of its own, so one password never hashes the same twice`() {
        val first = Passwords.hash(PASSWORD)
        val second = Passwords.hash(PASSWORD)

        assertNotEquals(first, second)
        assertNotEquals(first.split("$")[2], second.split("$")[2], "the salts differ")
        assertTrue(Passwords.verify(PASSWORD, first) && Passwords.verify(PASSWORD, second))
    }

    @Test
    fun `the stored form names its algorithm and cost and holds a 16-byte salt and a 32-byte hash`() {
        val (algorithm, iterations, salt, hash) = Passwords.hash(PASSWORD).split("$")

        assertEquals("pbkdf2-sha256", algorithm)
        assertEquals(Passwords.ITERATIONS.toString(), iterations)
        assertEquals(16, Base64.getDecoder().decode(salt).size)
        assertEquals(32, Base64.getDecoder().decode(hash).size)
    }

    /** What lets the cost be raised later: a hash stored at the old one still verifies, at its own. */
    @Test
    fun `a hash made at another cost verifies at the cost it names`() {
        val older = Passwords.hash(PASSWORD, iterations = 1_000)

        assertEquals("1000", older.split("$")[1])
        assertTrue(Passwords.verify(PASSWORD, older))
        assertFalse(Passwords.verify("not it", older))
    }

    @Test
    fun `a password of any characters hashes and verifies`() {
        listOf("with spaces in it", "tab\there", "ünïcødé ✓", "emoji 😀", "x".repeat(128)).forEach {
            assertTrue(Passwords.verify(it, Passwords.hash(it)), it)
        }
    }

    @Test
    fun `the unmatchable hash matches none of the passwords tried`() {
        listOf(PASSWORD, "password", "123456", "x".repeat(128)).forEach { presented ->
            assertFalse(Passwords.verify(presented, Passwords.UNMATCHABLE), presented)
        }
    }

    /** No hash this server made, so no login can pass on one, and the message never holds what was stored. */
    @Test
    fun `a stored string not in the server's form fails loudly and names nothing of it`() {
        val made = Passwords.hash(PASSWORD).split("$")
        listOf(
            "the password itself" to PASSWORD,
            "another algorithm" to (listOf("bcrypt") + made.drop(1)).joinToString("$"),
            "no cost" to (made.take(1) + "" + made.drop(2)).joinToString("$"),
            "a cost of 0" to (made.take(1) + "0" + made.drop(2)).joinToString("$"),
            "a salt that is not Base64" to (made.take(2) + "not base64!" + made.drop(3)).joinToString("$"),
            "a hash cut short" to (made.take(3) + made[3].dropLast(4)).joinToString("$"),
            "a part more" to (made + "extra").joinToString("$"),
        ).forEach { (case, stored) ->
            val refusal = assertFailsWith<IllegalStateException>(case) { Passwords.verify(PASSWORD, stored) }
            assertFalse(stored in refusal.message.orEmpty(), case)
        }
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
    }
}
