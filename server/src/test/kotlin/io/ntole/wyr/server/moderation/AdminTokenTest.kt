package io.ntole.wyr.server.moderation

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the admin token accepts, and how it compares. A timing leak cannot be measured reliably in a
 * test, so the constant-time comparison is pinned by what it is and what it is given.
 */
class AdminTokenTest {
    @Test
    fun `only the token itself matches`() {
        val token = AdminToken(TOKEN)

        assertTrue(token.matches(TOKEN))
        listOf(
            "none" to null,
            "empty" to "",
            "cut short" to TOKEN.dropLast(1),
            "and more" to TOKEN + "0",
            "in another case" to TOKEN.uppercase(),
            "padded" to " $TOKEN",
            "as a bearer" to "Bearer $TOKEN",
        ).forEach { (case, presented) -> assertFalse(token.matches(presented), case) }
    }

    @Test
    fun `every check is one comparison of two SHA-256 digests whatever is presented`() {
        val compared = mutableListOf<Pair<Int, Int>>()
        val token =
            AdminToken(TOKEN) { presented, expected ->
                compared += presented.size to expected.size
                MessageDigest.isEqual(presented, expected)
            }
        val presented = listOf("", "x", TOKEN.take(1), TOKEN, TOKEN.repeat(100))

        presented.forEach { token.matches(it) }

        // Equal lengths, whatever the length presented: the comparison's time cannot give the token's away.
        assertEquals(List(presented.size) { SHA_256_BYTES to SHA_256_BYTES }, compared)
    }

    @Test
    fun `the comparison alone decides`() {
        // Nothing before it refuses a wrong token early, a check of its length or first character
        // included, which would take less time for some guesses than others.
        assertTrue(AdminToken(TOKEN) { _, _ -> true }.matches("x"))
        assertFalse(AdminToken(TOKEN) { _, _ -> false }.matches(TOKEN))
    }

    @Test
    fun `the comparison is MessageDigest's constant-time one unless a test passes another`() {
        assertEquals<Any>(MessageDigest::isEqual, AdminToken(TOKEN).sameBytes)
    }

    private companion object {
        const val TOKEN = "test-admin-token-0123456789abcdef"
        const val SHA_256_BYTES = 32
    }
}
