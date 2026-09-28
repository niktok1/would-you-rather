package io.ntole.wyr.about

/**
 * The version of the app running, as its entry point read it from the build (CLAUDE.md §8g, *The
 * version is named once*): [name], MAJOR.MINOR.PATCH, and the build [number] made from it, or null
 * where the build could not say (a desktop app started without its property). The About screen shows
 * both.
 */
data class AppVersion(
    val name: String,
    val number: Int?,
)
