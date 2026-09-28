package io.ntole.wyr.theme

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
}
