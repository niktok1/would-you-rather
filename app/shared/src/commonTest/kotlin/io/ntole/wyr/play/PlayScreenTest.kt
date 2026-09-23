package io.ntole.wyr.play

import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.core.domain.vote.VoteOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayScreenTest {
    @Test
    fun `a vote shows the points the server awarded it`() {
        assertEquals("+1", pointsThisVote(outcome(pointsAwarded = 1, replayed = false)))
    }

    @Test
    fun `a replayed vote shows no points of its own`() {
        // The Play tab's retry of a vote whose response was lost: it landed and paid the first time.
        assertNull(pointsThisVote(outcome(pointsAwarded = 0, replayed = true)))
    }

    private fun outcome(
        pointsAwarded: Int,
        replayed: Boolean,
    ): VoteOutcome =
        VoteOutcome(
            questionId = "q1",
            yourSide = Side.A,
            tally = Tally(votesA = 1, votesB = 0),
            pointsAwarded = pointsAwarded,
            totalPoints = 1,
            replayed = replayed,
        )
}
