package io.ntole.wyr

import io.ntole.wyr.analytics.AnalyticsSettings

/**
 * The environment variable that names the desktop client's server environment (CLAUDE.md §8e):
 * local, dev or prod. `WYR_ENV=dev ./gradlew :app:desktopApp:run` passes it on to the app.
 */
internal const val ENVIRONMENT_VARIABLE: String = "WYR_ENV"

/**
 * The name [ENVIRONMENT_VARIABLE] gives in [variables], as it is, or `null` when it is unset, which
 * [io.ntole.wyr.di.initKoin] reads as local; a name it does not know stops the app there.
 *
 * Here rather than in `:app:desktopApp`, which only hands it on, since shared code can read it
 * (CLAUDE.md §3 entry-point rule). It reads [variables], the process's own unless a test gives others.
 */
fun desktopEnvironmentName(variables: Map<String, String> = System.getenv()): String? = variables[ENVIRONMENT_VARIABLE]

/** The variable that names the PostHog project's key the desktop client sends analytics to (CLAUDE.md §8g). */
internal const val POSTHOG_KEY_VARIABLE: String = "WYR_POSTHOG_KEY"

/** The variable that names that project's host, PostHog's EU cloud when unset. */
internal const val POSTHOG_HOST_VARIABLE: String = "WYR_POSTHOG_HOST"

/** The system property the desktop build names the app's version in (`:app:desktopApp`'s `jvmArgs`). */
internal const val APP_VERSION_PROPERTY: String = "wyr.app.version"

/**
 * The analytics the desktop client sends, as [variables] name them, the process's own unless a test
 * gives others: [POSTHOG_KEY_VARIABLE], none being analytics off, and [POSTHOG_HOST_VARIABLE], as
 * `WYR_ENV` names the environment, `WYR_POSTHOG_KEY=phc_... ./gradlew :app:desktopApp:run`; and the
 * app's version, from [APP_VERSION_PROPERTY] in [properties].
 */
fun desktopAnalyticsSettings(
    variables: Map<String, String> = System.getenv(),
    properties: Map<String, String> = System.getProperties().stringPropertyNames().associateWith(System::getProperty),
): AnalyticsSettings =
    AnalyticsSettings(
        key = variables[POSTHOG_KEY_VARIABLE],
        host = variables[POSTHOG_HOST_VARIABLE],
        appVersion = properties[APP_VERSION_PROPERTY].orEmpty(),
    )

/** The system property the desktop build names its build number in (`:app:desktopApp`'s `jvmArgs`). */
internal const val BUILD_NUMBER_PROPERTY: String = "wyr.app.build"

/**
 * The build number [BUILD_NUMBER_PROPERTY] names in [properties], the process's own unless a test gives
 * others, or null when it names no whole number: a desktop app started without it, from an IDE say,
 * whose requests then name no build (CLAUDE.md §8b, *Minimum client version*).
 */
fun desktopBuildNumber(
    properties: Map<String, String> = System.getProperties().stringPropertyNames().associateWith(System::getProperty),
): Int? = properties[BUILD_NUMBER_PROPERTY]?.trim()?.toIntOrNull()
