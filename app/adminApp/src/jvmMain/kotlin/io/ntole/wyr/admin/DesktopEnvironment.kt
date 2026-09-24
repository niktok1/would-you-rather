package io.ntole.wyr.admin

/**
 * The environment variable that names the server the desktop app moderates (CLAUDE.md §8e): local,
 * dev or prod, the same variable as the game's desktop client. `WYR_ENV=dev ./gradlew
 * :app:adminApp:run` passes it on to the app.
 */
internal const val ENVIRONMENT_VARIABLE: String = "WYR_ENV"

/**
 * The name [ENVIRONMENT_VARIABLE] gives in [variables], as it is, or `null` when it is unset, which
 * [io.ntole.wyr.admin.di.initAdminKoin] reads as local; a name it does not know stops the app there.
 * It reads [variables], the process's own unless a test gives others.
 */
internal fun desktopEnvironmentName(variables: Map<String, String> = System.getenv()): String? =
    variables[ENVIRONMENT_VARIABLE]
