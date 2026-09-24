package io.ntole.wyr

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import io.ntole.wyr.di.initKoin

/**
 * [WYR_ENV] is the environment the build was made for, `-Pwyr.env` (CLAUDE.md §8e). The server it
 * names must list this page's origin in its `ALLOWED_WEB_ORIGINS`, or every request fails CORS.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    initKoin(environmentName = WYR_ENV)

    ComposeViewport {
        App()
    }
}
