package io.ntole.wyr.analytics

import io.ntole.wyr.analytics.RecordingAnalytics.Recorded
import io.ntole.wyr.core.domain.analytics.AnalyticsEvent
import io.ntole.wyr.core.domain.analytics.AnalyticsProperty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * What the analytics hear of the app's comings and goings and of its screens (CLAUDE.md §8g): what
 * PostHog counts live players, sessions and the time on each screen from.
 */
class UsageTrackerTest {
    private val analytics = RecordingAnalytics()
    private val clock = TestTimeSource()
    private val usage = UsageTracker(analytics, clock)

    @Test
    fun `a launch opens the app then shows its first screen`() {
        usage.foreground("sr-Cyrl")
        usage.show("home")

        assertEquals(
            listOf(opened(fromBackground = false), screen("home")),
            analytics.recorded,
        )
    }

    @Test
    fun `a screen shown leaves the one before after the time on it`() {
        usage.foreground("sr-Cyrl")
        usage.show("home")
        clock += 3.seconds

        usage.show("play")

        assertEquals(listOf(left("home", 3_000), screen("play")), analytics.recorded.drop(2))
    }

    /** A composition made anew, as a rotation's is, shows the screen it showed: nothing new. */
    @Test
    fun `the screen shown already is nothing new`() {
        usage.foreground("sr-Cyrl")
        usage.show("play")

        usage.show("play")

        assertEquals(listOf(opened(fromBackground = false), screen("play")), analytics.recorded)
    }

    @Test
    fun `the background leaves the screen and sends what waits`() {
        usage.foreground("en")
        usage.show("play")
        clock += 5.seconds

        usage.background(configurationChanging = false)

        assertEquals(
            listOf(left("play", 5_000), backgrounded(5_000), Recorded(RecordingAnalytics.FLUSH)),
            analytics.recorded.drop(2),
        )
    }

    @Test
    fun `back from the background the app opens again on the screen it left`() {
        usage.foreground("en")
        usage.show("play")
        usage.background(configurationChanging = false)
        clock += 60.seconds

        usage.foreground("en")
        clock += 2.seconds
        usage.show("account")

        assertEquals(
            listOf(
                opened(fromBackground = true, language = "en"),
                screen("play"),
                left("play", 2_000),
                screen("account"),
            ),
            analytics.recorded.drop(5),
        )
    }

    /** Android's activity made anew on a rotation stops and starts again: nothing to report. */
    @Test
    fun `a rotation is neither the background nor a new opening`() {
        usage.foreground("sr-Cyrl")
        usage.show("play")
        val before = analytics.recorded.toList()

        usage.background(configurationChanging = true)
        usage.foreground("sr-Cyrl")
        usage.show("play")

        assertEquals(before, analytics.recorded)
    }

    /** A screen the navigator shows before the lifecycle says the app is in the foreground waits for it. */
    @Test
    fun `a screen shown before the app is in the foreground is shown with it`() {
        usage.show("home")
        assertEquals(emptyList(), analytics.recorded)

        usage.foreground("sr-Cyrl")

        assertEquals(listOf(opened(fromBackground = false), screen("home")), analytics.recorded)
    }

    @Test
    fun `a second start or stop in a row is nothing new`() {
        usage.foreground("sr-Cyrl")
        usage.foreground("sr-Cyrl")
        usage.background(configurationChanging = false)
        usage.background(configurationChanging = false)

        assertEquals(1, analytics.named(AnalyticsEvent.APP_OPENED).size)
        assertEquals(1, analytics.named(AnalyticsEvent.APP_BACKGROUNDED).size)
    }

    private fun opened(
        fromBackground: Boolean,
        language: String = "sr-Cyrl",
    ) = Recorded(
        AnalyticsEvent.APP_OPENED,
        mapOf(AnalyticsProperty.FROM_BACKGROUND to fromBackground, AnalyticsProperty.LANGUAGE to language),
    )

    private fun screen(name: String) =
        Recorded(RecordingAnalytics.SCREEN, mapOf(RecordingAnalytics.SCREEN_NAME to name))

    private fun left(
        name: String,
        millis: Long,
    ) = Recorded(
        AnalyticsEvent.SCREEN_LEFT,
        mapOf(AnalyticsProperty.SCREEN to name, AnalyticsProperty.DURATION_MS to millis),
    )

    private fun backgrounded(millis: Long) =
        Recorded(
            AnalyticsEvent.APP_BACKGROUNDED,
            mapOf(AnalyticsProperty.DURATION_MS to millis),
        )
}
