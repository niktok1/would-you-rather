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

    /** The form a question is written in, reached from My questions on the Account screen (§8d, *Submitting*). */
    data object Submit : Screen("submit")

    /** The page a guest registers or logs in on, reached from the Account screen (§8d, *The Account screen*). */
    data object Auth : Screen("auth")

    /** The categories played, picked and searched; reached from the Play screen (§8d, *Categories*). */
    data object Categories : Screen("categories")

    /** The game's version, its legal pages and its libraries' licences; reached from the Account screen (§8d, *About*). */
    data object About : Screen("about")

    internal companion object {
        // Listed on each call, not kept in a property: the companion's properties are set up with the
        // class, before the objects are when one of them is used first, so a kept list could hold nulls.
        fun ofKey(key: String): Screen? =
            listOf(Home, Play, Account, Submit, Auth, Categories, About).firstOrNull { it.key == key }
    }
}
