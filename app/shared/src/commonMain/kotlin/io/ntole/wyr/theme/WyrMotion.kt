package io.ntole.wyr.theme

import kotlin.math.PI
import kotlin.math.sin

/**
 * The timing and size of the motion shared across screens (CLAUDE.md §5b, *Motion*): short, and none
 * looping forever. How far a thing slides is a [WyrDimens] value. A screen's own motion, the Play
 * screen's cards and the Home screen's reveal, keeps its timing beside it.
 *
 * Each is animated where it is drawn or placed, never by composing anything again a frame, and each
 * ends exactly where the screen stood before there was motion. Compose scales every duration by the
 * platform's animator duration scale by itself: Android's *Remove animations* ends each at once.
 */
object WyrMotion {
    /** A screen coming in over the one before, or going back to it: a fade and a slight slide. */
    const val SCREEN_MILLIS: Int = 250

    /** The coin's bump as the points go up. */
    const val COIN_BUMP_MILLIS: Int = 250

    /** How large the coin bumps, at its largest. */
    const val COIN_BUMP_SCALE: Float = 1.3f

    /**
     * A there-and-back at [progress], from 0 to 1: 0 at either end and 1 halfway, so an animation made of
     * it starts and ends at rest, exactly.
     */
    fun bump(progress: Float): Float = if (progress <= 0f || progress >= 1f) 0f else sin(PI.toFloat() * progress)
}
