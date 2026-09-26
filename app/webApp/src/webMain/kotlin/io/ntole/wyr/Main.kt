package io.ntole.wyr

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import io.ntole.wyr.analytics.AnalyticsSettings
import io.ntole.wyr.di.initKoin

/**
 * [WYR_ENV] is the environment the build was made for, `-Pwyr.env` (CLAUDE.md §8e). The server it
 * names must list this page's origin in its `ALLOWED_WEB_ORIGINS`, or every request fails CORS.
 * [POSTHOG_KEY] and [POSTHOG_HOST] are the analytics project it sends to, from `wyr.posthog.key` and
 * `wyr.posthog.host` (§8g), none being off. [BUILD_NUMBER] is the build every request names (§8b,
 * *Minimum client version*), made from `wyr.app.version`.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    initKoin(
        environmentName = WYR_ENV,
        analytics = AnalyticsSettings(key = POSTHOG_KEY, host = POSTHOG_HOST, appVersion = APP_VERSION),
        build = BUILD_NUMBER,
    )

    ComposeViewport {
        App()
    }
}
