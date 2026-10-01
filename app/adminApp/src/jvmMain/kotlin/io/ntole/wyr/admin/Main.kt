package io.ntole.wyr.admin

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.ntole.wyr.admin.di.initAdminKoin

/**
 * The `WYR_ENV` environment variable names the server to moderate ([desktopEnvironmentName]), and
 * `WYR_ADMIN_TOKEN`, which Gradle's run tasks set from local.properties, the token to start with
 * ([desktopAdminToken]).
 */
fun main() {
    val environment = initAdminKoin(environmentName = desktopEnvironmentName())

    application {
        Window(onCloseRequest = ::exitApplication, title = windowTitleOf(environment)) {
            AdminApp(initialToken = desktopAdminToken())
        }
    }
}
