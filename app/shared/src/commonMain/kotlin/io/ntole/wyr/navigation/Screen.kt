package io.ntole.wyr.navigation

/**
 * The game's screens (CLAUDE.md §8d, *Navigation*), each one place in the [Navigator]'s back stack.
 * [key] is what saved state keeps for it, which never changes with the objects' names.
 */
sealed class Screen(
    internal val key: String,
) {
    /** The game's name and Play; the app opens on it, and the back stack always starts with it. */
    data object Home : Screen("home")

    data object Play : Screen("play")

    data object Account : Screen("account")

    /** Reached from the Account screen for now (§8d, *The Submit screen*). */
    data object Submit : Screen("submit")

    /** The categories played, picked and searched; reached from the Play screen (§8d, *Categories*). */
    data object Categories : Screen("categories")

    internal companion object {
        // Listed on each call, not kept in a property: the companion's properties are set up with the
        // class, before the objects are when one of them is used first, so a kept list could hold nulls.
        fun ofKey(key: String): Screen? = listOf(Home, Play, Account, Submit, Categories).firstOrNull { it.key == key }
    }
}
