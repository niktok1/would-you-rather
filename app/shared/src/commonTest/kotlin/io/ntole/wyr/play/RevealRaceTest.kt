package io.ntole.wyr.play

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reveal's race (CLAUDE.md §8d, *The Play screen*): both sides climb together until the smaller
 * share, which stops there, and the larger then goes on to its own.
 */
class RevealRaceTest {
    private val steps = (0..STEPS).map { it / STEPS.toFloat() }

    @Test
    fun bothSidesClimbTogetherUntilTheSmallerShare() {
        steps.forEach { p ->
            val low = raceAt(p, 38, 62)
            val high = raceAt(p, 62, 38)
            if (low < 38f) assertEquals(low, high, "apart at $p before the smaller share")
        }
    }

    @Test
    fun theSmallerStopsAtItsShareAndTheLargerGoesOnToItsOwn() {
        assertEquals(0f, raceAt(0f, 38, 62))
        assertEquals(0f, raceAt(0f, 62, 38))
        assertEquals(38f, raceAt(1f, 38, 62))
        assertEquals(62f, raceAt(1f, 62, 38))
        val stopped = steps.first { raceAt(it, 38, 62) >= 38f }
        assertTrue(stopped < 1f, "the smaller share is reached before the end")
        assertTrue(raceAt(stopped, 62, 38) < 62f, "the larger is still on its way then")
    }

    @Test
    fun eachSideOnlyEverClimbs() {
        listOf(38 to 62, 62 to 38, 50 to 50, 3 to 97, 97 to 3, 0 to 100, 100 to 0).forEach { (target, rival) ->
            steps.zipWithNext().forEach { (a, b) ->
                assertTrue(raceAt(b, target, rival) >= raceAt(a, target, rival), "$target against $rival at $b")
            }
        }
    }

    @Test
    fun aTieClimbsTogetherTheWholeTime() {
        steps.forEach { p -> assertTrue(raceAt(p, 50, 50) <= 50f) }
        assertEquals(50f, raceAt(1f, 50, 50))
        assertTrue(raceAt(0.9f, 50, 50) < 50f, "a tie is still climbing near the end")
    }

    @Test
    fun aLopsidedResultDoesNotCrawlToItsSmallerShare() {
        val stopped = steps.first { raceAt(it, 3, 97) >= 3f }
        assertTrue(stopped <= 0.25f, "the 3 is reached at $stopped of the time")
    }

    private companion object {
        const val STEPS = 200
    }
}
