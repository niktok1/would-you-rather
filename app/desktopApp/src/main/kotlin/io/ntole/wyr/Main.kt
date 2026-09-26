package io.ntole.wyr

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.ntole.wyr.di.initKoin

/**
 * The `WYR_ENV` environment variable names the server environment ([desktopEnvironmentName]), and
 * `WYR_POSTHOG_KEY` the analytics project ([desktopAnalyticsSettings]).
 */
fun main() {
    initKoin(environmentName = desktopEnvironmentName(), analytics = desktopAnalyticsSettings())

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "WYR",
        ) {
            App()
        }
    }
}
