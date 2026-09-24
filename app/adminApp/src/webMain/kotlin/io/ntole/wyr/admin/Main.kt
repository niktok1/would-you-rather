package io.ntole.wyr.admin

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import io.ntole.wyr.admin.di.initAdminKoin

/**
 * [WYR_ENV] is the server the build was made for, `-Pwyr.env` (CLAUDE.md §8e). That server must list
 * this page's origin in its `ALLOWED_WEB_ORIGINS`, or every request fails CORS, the admin token's
 * header included.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    initAdminKoin(environmentName = WYR_ENV)

    ComposeViewport {
        AdminApp()
    }
}
