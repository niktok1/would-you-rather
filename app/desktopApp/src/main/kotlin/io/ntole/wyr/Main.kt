package io.ntole.wyr

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.ntole.wyr.di.initKoin

/**
 * The `WYR_ENV` environment variable names the server environment (CLAUDE.md §8e): local, dev or
 * prod, local when unset. `WYR_ENV=dev ./gradlew :app:desktopApp:run` passes it on to the app.
 */
fun main() {
    initKoin(environmentName = System.getenv("WYR_ENV"))

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "WYR",
        ) {
            App()
        }
    }
}
