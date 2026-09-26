package io.ntole.wyr

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.ntole.wyr.di.initKoin
import kotlin.system.exitProcess

/**
 * The `WYR_ENV` environment variable names the server environment ([desktopEnvironmentName]),
 * `WYR_POSTHOG_KEY` the analytics project ([desktopAnalyticsSettings]), and the `wyr.app.build` system
 * property the build number every request names ([desktopBuildNumber]).
 *
 * Once the window is closed, and before the process exits, the app's last analytics are sent
 * ([endDesktopApp]): the JVM would otherwise end with them waiting, or cut short (CLAUDE.md §8g).
 */
fun main() {
    initKoin(
        environmentName = desktopEnvironmentName(),
        analytics = desktopAnalyticsSettings(),
        build = desktopBuildNumber(),
    )

    application(exitProcessOnExit = false) {
        Window(
            onCloseRequest = ::exitApplication,
            title = "WYR",
        ) {
            App()
        }
    }
    endDesktopApp()
    exitProcess(0)
}
