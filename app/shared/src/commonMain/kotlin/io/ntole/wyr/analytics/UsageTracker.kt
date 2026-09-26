package io.ntole.wyr.analytics

import io.ntole.wyr.core.domain.analytics.Analytics
import io.ntole.wyr.core.domain.analytics.AnalyticsEvent
import io.ntole.wyr.core.domain.analytics.AnalyticsProperty
import kotlin.time.ComparableTimeMark
import kotlin.time.TimeSource

/**
 * The app's comings and goings, for [analytics] (CLAUDE.md §8g): the app opened, at launch and from the
 * background, and backgrounded, after how long; every screen shown, and the time spent on the one
 * left, for another or for the background. The screens from which PostHog counts live players,
 * sessions and where they stay.
 *
 * One for the app's life (bound once in Koin), so an Android activity made anew on a rotation finds it
 * as it was, and told of that rotation ([background] with `configurationChanging`), which it lets
 * pass as though the app had stayed in the foreground. On the main thread, where the lifecycle and
 * the composition call it.
 */
class UsageTracker(
    private val analytics: Analytics,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    private var launched = false
    private var foregroundSince: ComparableTimeMark? = null
    private var restartingForConfiguration = false
    private var screen: String? = null
    private var screenSince: ComparableTimeMark? = null

    /**
     * The app came to the foreground, shown in [languageTag]: at launch, and from the background, when
     * the screen it went with is shown again.
     */
    fun foreground(languageTag: String) {
        if (restartingForConfiguration) {
            restartingForConfiguration = false
            return
        }
        if (foregroundSince != null) return
        val now = timeSource.markNow()
        foregroundSince = now
        analytics.track(
            AnalyticsEvent.APP_OPENED,
            mapOf(AnalyticsProperty.FROM_BACKGROUND to launched, AnalyticsProperty.LANGUAGE to languageTag),
        )
        launched = true
        screen?.let { name -> showNow(name, now) }
    }

    /**
     * The app went to the background: the screen shown is left, and what waits is sent now, since the
     * app may not come back. [configurationChanging] is Android's rotation, whose activity stops only
     * to start again at once, and which is nothing to report.
     */
    fun background(configurationChanging: Boolean) {
        if (configurationChanging) {
            restartingForConfiguration = foregroundSince != null
            return
        }
        val since = foregroundSince ?: return
        leaveScreen()
        analytics.track(
            AnalyticsEvent.APP_BACKGROUNDED,
            mapOf(AnalyticsProperty.DURATION_MS to since.elapsedNow().inWholeMilliseconds),
        )
        foregroundSince = null
        analytics.flush()
    }

    /**
     * The screen [name] is shown (a `Screen`'s key): the one before is left, after the time spent on
     * it. The screen shown already, as a composition made anew shows it, is nothing new; one shown
     * again from the background is [foreground]'s.
     */
    fun show(name: String) {
        if (name == screen) return
        leaveScreen()
        screen = name
        // In the background, it is shown once the app comes back.
        foregroundSince?.let { showNow(name, timeSource.markNow()) }
    }

    private fun showNow(
        name: String,
        at: ComparableTimeMark,
    ) {
        screenSince = at
        analytics.screen(name)
    }

    private fun leaveScreen() {
        val name = screen ?: return
        val since = screenSince ?: return
        analytics.track(
            AnalyticsEvent.SCREEN_LEFT,
            mapOf(
                AnalyticsProperty.SCREEN to name,
                AnalyticsProperty.DURATION_MS to since.elapsedNow().inWholeMilliseconds,
            ),
        )
        screenSince = null
    }
}
