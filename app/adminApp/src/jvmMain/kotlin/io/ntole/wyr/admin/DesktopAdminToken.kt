package io.ntole.wyr.admin

/**
 * The environment variable holding the admin token the desktop app starts with (CLAUDE.md §8d,
 * *Moderation*). `./gradlew :app:adminApp:run` sets it from local.properties' `wyr.admin.token.<env>`,
 * the one for the server `WYR_ENV` names, and never passes on one the shell had.
 */
internal const val ADMIN_TOKEN_VARIABLE: String = "WYR_ADMIN_TOKEN"

/**
 * The token [ADMIN_TOKEN_VARIABLE] gives in [variables], or `null` when it is unset or blank, so the
 * field starts empty. It reads [variables], the process's own unless a test gives others.
 */
internal fun desktopAdminToken(variables: Map<String, String> = System.getenv()): String? =
    variables[ADMIN_TOKEN_VARIABLE]?.takeIf { it.isNotBlank() }
