package io.ntole.wyr.about

/**
 * One library the game ships with, [name], and the licence it is under, [licence], whose text is at
 * [licenceUrl].
 */
data class Licensed(
    val name: String,
    val licence: String,
    val licenceUrl: String,
)

private const val APACHE_2 = "Apache License 2.0"
private const val APACHE_2_URL = "https://www.apache.org/licenses/LICENSE-2.0"

/**
 * The open-source libraries the game ships with, on any of its platforms, and each one's licence
 * (CLAUDE.md §8d, *About*), written by hand, no library for it: the game's own dependencies in
 * `gradle/libs.versions.toml` and what they bring with them into an app. Every one is Apache 2.0 today.
 * A library added to the game's client is added here in the same change.
 */
val OPEN_SOURCE_LIBRARIES: List<Licensed> =
    listOf(
        "Kotlin",
        "kotlinx.coroutines",
        "kotlinx.serialization",
        "kotlinx-datetime",
        "Compose Multiplatform",
        "Skiko",
        "AndroidX Activity",
        "AndroidX Lifecycle",
        "Ktor",
        "OkHttp",
        "Okio",
        "Koin",
        "Kotlin Wrappers",
    ).map { name -> Licensed(name, APACHE_2, APACHE_2_URL) }

/** What the About screen says the game is for, the store listing's and the terms' age (CLAUDE.md §8b). */
const val AGE_RATING: String = "16+"
