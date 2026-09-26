package io.ntole.wyr.analytics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import io.ntole.wyr.core.domain.analytics.Analytics
import io.ntole.wyr.core.domain.analytics.AnalyticsEvent
import io.ntole.wyr.core.domain.analytics.AnalyticsProperty

/**
 * The analytics the game's screens report taps to (CLAUDE.md §8g): the app's, which `App` provides,
 * and none for a screen drawn on its own, as a test draws one.
 */
val LocalAnalytics = staticCompositionLocalOf { Analytics.None }

/**
 * [onClick], reporting first a tap on [element], with [properties] of its own, to [LocalAnalytics]
 * (CLAUDE.md §8g): what every button, card and line of the game hands its `onClick`, so a tap is counted
 * wherever it lands, by a name that never changes with the language, the text or the layout.
 *
 * An element is named `screen.what`, in lower case and underscores (`play.card_a`, `top_bar.back`):
 * once sent it never changes, or the dashboards built on it lose it. A new button gets one this way,
 * and `TapsTest` taps everything tappable on every screen and fails on one that reports nothing.
 */
@Composable
fun tapped(
    element: String,
    properties: Map<String, Any?> = emptyMap(),
    onClick: () -> Unit,
): () -> Unit {
    val analytics = LocalAnalytics.current
    val action by rememberUpdatedState(onClick)
    return remember(analytics, element, properties) {
        {
            analytics.track(AnalyticsEvent.TAP, mapOf(AnalyticsProperty.ELEMENT to element) + properties)
            action()
        }
    }
}
