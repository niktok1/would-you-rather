package io.ntole.wyr.play

import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * How long something has been on screen, counted only while its screen is shown (CLAUDE.md §8g): it
 * stops while the screen is [hidden], another screen shown over it or the app in the background, and
 * goes on once it is [shown] again. [start] begins the count anew, for what the screen shows next.
 *
 * The screen counts as shown until told otherwise, since the one that makes it is on screen. On the main
 * thread, as its ViewModel and the composition that tells it are.
 */
internal class ScreenStopwatch(
    private val timeSource: TimeSource.WithComparableMarks,
) {
    private var onScreen = true
    private var counting = false
    private var counted = Duration.ZERO
    private var since: ComparableTimeMark? = null

    /** Counts from zero, and from now if the screen is shown. */
    fun start() {
        counting = true
        counted = Duration.ZERO
        since = if (onScreen) timeSource.markNow() else null
    }

    /** The screen is shown again: the count goes on from now. */
    fun shown() {
        if (onScreen) return
        onScreen = true
        if (counting) since = timeSource.markNow()
    }

    /** The screen is hidden: the count stops where it is. */
    fun hidden() {
        if (!onScreen) return
        onScreen = false
        since?.let { counted += it.elapsedNow() }
        since = null
    }

    /** What has been counted since [start], or null if nothing was started. */
    fun elapsed(): Duration? = if (counting) counted + (since?.elapsedNow() ?: Duration.ZERO) else null
}
