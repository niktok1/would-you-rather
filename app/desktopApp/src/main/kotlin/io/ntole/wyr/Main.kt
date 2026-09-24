package io.ntole.wyr

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.ntole.wyr.di.initKoin

/** The `WYR_ENV` environment variable names the server environment ([desktopEnvironmentName]). */
fun main() {
    initKoin(environmentName = desktopEnvironmentName())

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "WYR",
        ) {
            App()
        }
    }
}
