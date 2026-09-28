package io.ntole.wyr.services

import android.app.Activity
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Which activity [ActivityTracker] names as on screen, told its lifecycle as Android tells it. The
 * Android jar of a host test is stubs, so each activity is a bare subclass that only says whether it is
 * finishing.
 */
class ActivityTrackerTest {
    private val tracker = ActivityTracker()

    /** Play Games' sign-in, translucent over the app's activity, which is paused and never stopped. */
    @Test
    fun `the app's activity is on screen again once a translucent one over it stops`() {
        val app = TestActivity()
        val signIn = TestActivity()
        tracker.onActivityStarted(app)
        tracker.onActivityStarted(signIn)
        assertSame(signIn, tracker.current())

        signIn.finishing = true
        assertSame(app, tracker.current(), "finishing, before it stops")
        tracker.onActivityStopped(signIn)

        assertSame(app, tracker.current())
    }

    /** An opaque one over it stops the app's, which starts again as it finishes. */
    @Test
    fun `an opaque activity over the app's is on screen until the app's starts again`() {
        val app = TestActivity()
        val other = TestActivity()
        tracker.onActivityStarted(app)
        tracker.onActivityStarted(other)
        tracker.onActivityStopped(app)
        assertSame(other, tracker.current())

        other.finishing = true
        assertSame(other, tracker.current(), "the only one started")
        tracker.onActivityStarted(app)
        tracker.onActivityStopped(other)

        assertSame(app, tracker.current())
    }

    @Test
    fun `no activity is on screen once every one stopped`() {
        val app = TestActivity()
        assertNull(tracker.current(), "none started yet")
        tracker.onActivityStarted(app)

        tracker.onActivityStopped(app)

        assertNull(tracker.current())
    }

    private class TestActivity : Activity() {
        var finishing = false

        override fun isFinishing(): Boolean = finishing
    }
}
