package io.ntole.wyr

import io.ntole.wyr.analytics.RecordingAnalytics
import io.ntole.wyr.analytics.UsageTracker
import io.ntole.wyr.core.domain.analytics.AnalyticsEvent
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** The desktop app's end (CLAUDE.md §8g): its last events sent, and never a wait past its bound. */
class DesktopEndTest {
    private val analytics = RecordingAnalytics()
    private val usage = UsageTracker(analytics)

    @Test
    fun `the end reports the background and waits for the send`() {
        usage.foreground("sr-Cyrl")

        endApp(usage, LAST_SEND_WAIT)

        assertEquals(1, analytics.named(AnalyticsEvent.APP_BACKGROUNDED).size)
        assertEquals(1, analytics.named(RecordingAnalytics.FLUSH_AND_WAIT).size)
    }

    /** Offline, or a service that does not answer: the app ends all the same once the wait is up. */
    @Test
    fun `the end waits no longer than its bound`() {
        analytics.sendsFinish = CompletableDeferred()
        usage.foreground("sr-Cyrl")
        val started = TimeSource.Monotonic.markNow()

        endApp(usage, 100.milliseconds)

        val waited = started.elapsedNow()
        assertTrue(waited >= 100.milliseconds && waited < LAST_SEND_WAIT, "waited $waited")
    }
}
