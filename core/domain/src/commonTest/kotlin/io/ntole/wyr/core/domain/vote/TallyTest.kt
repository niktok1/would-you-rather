package io.ntole.wyr.core.domain.vote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TallyTest {
    @Test
    fun `percentages always sum to one hundred`() {
        // The complement definition of percentB exists to make this true for every input,
        // including the thirds that independent rounding would render as 33/33.
        for (a in 0L..40L) {
            for (b in 0L..40L) {
                if (a + b == 0L) continue
                val tally = Tally(votesA = a, votesB = b)
                assertEquals(
                    expected = 100,
                    actual = tally.percentA + tally.percentB,
                    message = "$a/$b gave ${tally.percentA}+${tally.percentB}",
                )
            }
        }
    }

    @Test
    fun `rounds half up`() {
        assertEquals(50, Tally(votesA = 1, votesB = 1).percentA)
        assertEquals(33, Tally(votesA = 1, votesB = 2).percentA)
        assertEquals(67, Tally(votesA = 2, votesB = 1).percentA)
        assertEquals(17, Tally(votesA = 1, votesB = 5).percentA)
        assertEquals(100, Tally(votesA = 1, votesB = 0).percentA)
        assertEquals(0, Tally(votesA = 0, votesB = 1).percentA)
    }

    @Test
    fun `empty tally reports zero rather than a fabricated split`() {
        val empty = Tally(votesA = 0, votesB = 0)
        assertFalse(empty.hasVotes)
        assertEquals(0, empty.percentA)
        assertEquals(0, empty.percentB)
        assertEquals(0L, empty.total)
        assertNull(empty.majority)
    }

    @Test
    fun `majority is null only on an exact tie`() {
        assertEquals(Side.A, Tally(votesA = 3, votesB = 2).majority)
        assertEquals(Side.B, Tally(votesA = 2, votesB = 3).majority)
        assertNull(Tally(votesA = 2, votesB = 2).majority)
    }

    @Test
    fun `negative counts are rejected`() {
        assertFailsWith<IllegalArgumentException> { Tally(votesA = -1, votesB = 0) }
        assertFailsWith<IllegalArgumentException> { Tally(votesA = 0, votesB = -1) }
    }

    @Test
    fun `a tie counts as agreeing with the majority`() {
        val outcome =
            VoteOutcome(
                questionId = "q1",
                yourSide = Side.A,
                tally = Tally(votesA = 5, votesB = 5),
                pointsAwarded = 10,
                totalPoints = 10,
            )
        assertTrue(outcome.agreedWithMajority)
    }

    @Test
    fun `side other flips`() {
        assertEquals(Side.B, Side.A.other)
        assertEquals(Side.A, Side.B.other)
    }
}
