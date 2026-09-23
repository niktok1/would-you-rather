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
}
