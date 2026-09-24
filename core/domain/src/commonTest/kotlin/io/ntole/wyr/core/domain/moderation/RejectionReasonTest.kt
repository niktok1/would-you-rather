package io.ntole.wyr.core.domain.moderation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The server's rules for a reason (`checkedRejection`), as the moderator's client applies them. */
class RejectionReasonTest {
    @Test
    fun `a reason is taken trimmed as the server stores it`() {
        assertEquals("a duplicate", RejectionReason.of("  a duplicate \n")?.value)
    }

    @Test
    fun `a blank reason is refused`() {
        listOf("", " ", "\t\n ").forEach { text -> assertNull(RejectionReason.of(text), "\"$text\"") }
    }

    @Test
    fun `a reason of the longest length fits and one more does not`() {
        val longest = "x".repeat(RejectionReason.MAX_LENGTH)

        assertEquals(longest, RejectionReason.of(longest)?.value)
        assertNull(RejectionReason.of(longest + "x"))
    }

    @Test
    fun `padding does not count towards the length`() {
        val longest = "x".repeat(RejectionReason.MAX_LENGTH)

        assertEquals(longest, RejectionReason.of("  $longest  ")?.value)
    }

    @Test
    fun `the length is counted in UTF-16 units as the server counts it`() {
        // Each emoji is two units, so half as many fill the limit.
        val longest = "😀".repeat(RejectionReason.MAX_LENGTH / 2)

        assertEquals(longest, RejectionReason.of(longest)?.value)
        assertNull(RejectionReason.of(longest + "x"))
    }

    @Test
    fun `a reason of more than one line is refused`() {
        // A whitespace control character goes at either end like a space, but none may be left inside.
        listOf("a\nb", "a\r\nb", "a\tb", "a\u2028b", "a\u2029b", "a\u0000b", "a\u007Fb").forEach { text ->
            assertNull(RejectionReason.of(text), "\"$text\"")
        }
    }
}
