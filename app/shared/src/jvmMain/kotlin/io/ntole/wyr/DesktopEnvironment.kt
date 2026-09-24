package io.ntole.wyr

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
