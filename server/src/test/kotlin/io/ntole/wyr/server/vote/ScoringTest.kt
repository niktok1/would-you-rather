package io.ntole.wyr.server.vote

import kotlin.test.Test
import kotlin.test.assertEquals

class ScoringTest {
    @Test
    fun `an answer is worth exactly one point`() {
        // The API tests check that every vote pays POINTS_PER_ANSWER, not what it is worth, so
        // this is what holds the value to CLAUDE.md §8d. Changing it is a game-rule decision.
        assertEquals(1, Scoring.POINTS_PER_ANSWER)
    }

    @Test
    fun `a like held is worth exactly one point to the author`() {
        // As above: the like tests check that a like pays POINTS_PER_LIKE, and this holds its value.
        assertEquals(1, Scoring.POINTS_PER_LIKE)
    }

    @Test
    fun `submitting costs one point where the server sets no cost`() {
        // The release sets SUBMISSION_COST to 50 on production (CLAUDE.md §8b, The launch); unset, it is 1.
        assertEquals(1, Scoring.DEFAULT_SUBMISSION_COST)
    }
}
