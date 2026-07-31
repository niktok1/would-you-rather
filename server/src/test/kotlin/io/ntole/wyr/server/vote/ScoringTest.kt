package io.ntole.wyr.server.vote

import io.ntole.wyr.core.vote.OptionSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScoringTest {
    @Test
    fun `minority pick keeps the base points but resets the streak`() {
        val award =
            Scoring.award(
                choice = OptionSide.B,
                votesA = 10,
                votesB = 3,
                previousStreak = 7,
            )

        assertFalse(award.agreedWithMajority)
        assertEquals(Scoring.BASE_POINTS, award.points)
        assertEquals(0, award.streak)
    }

    @Test
    fun `majority pick extends the streak and pays a growing bonus`() {
        val first = Scoring.award(OptionSide.A, votesA = 10, votesB = 3, previousStreak = 0)
        val later = Scoring.award(OptionSide.A, votesA = 10, votesB = 3, previousStreak = 4)

        assertTrue(first.agreedWithMajority)
        assertEquals(1, first.streak)
        assertEquals(5, later.streak)
        assertTrue(
            later.points > first.points,
            "a longer streak should pay more: ${later.points} vs ${first.points}",
        )
    }

    @Test
    fun `an exact tie counts as agreeing`() {
        // Matches VoteOutcome.agreedWithMajority on the client, which also treats a tie as
        // agreement. If these two ever disagree, the reveal screen contradicts the points.
        val award = Scoring.award(OptionSide.A, votesA = 4, votesB = 4, previousStreak = 0)

        assertTrue(award.agreedWithMajority)
        assertEquals(1, award.streak)
    }

    @Test
    fun `the streak bonus stops growing past the cap`() {
        val atCap =
            Scoring.award(
                OptionSide.A,
                votesA = 5,
                votesB = 1,
                previousStreak = Scoring.MAX_REWARDED_STREAK - 1,
            )
        val beyondCap =
            Scoring.award(
                OptionSide.A,
                votesA = 5,
                votesB = 1,
                previousStreak = Scoring.MAX_REWARDED_STREAK * 10,
            )

        assertEquals(atCap.points, beyondCap.points)
        // The streak itself keeps counting even though the bonus is capped — the player should
        // still see the real number.
        assertEquals(Scoring.MAX_REWARDED_STREAK * 10 + 1, beyondCap.streak)
    }

    @Test
    fun `the very first vote on a question is trivially the majority`() {
        val award = Scoring.award(OptionSide.A, votesA = 1, votesB = 0, previousStreak = 0)

        assertTrue(award.agreedWithMajority)
        assertEquals(Scoring.BASE_POINTS + Scoring.MAJORITY_BONUS + Scoring.PER_STREAK_BONUS, award.points)
    }
}
