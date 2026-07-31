package io.ntole.wyr

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.ntole.wyr.di.initKoin

fun main() {
    initKoin()

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "WYR",
        ) {
            App()
        }
    }
}
