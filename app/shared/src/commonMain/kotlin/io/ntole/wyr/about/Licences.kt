package io.ntole.wyr.about

/**
 * One library the game ships with, [name], the licence it is under, [licence], whose text is at
 * [licenceUrl], and the copyright [notice] that licence asks to ship with the app, for a licence that
 * asks for one (MIT's and BSD's; none for Apache 2.0's).
 */
data class Licensed(
    val name: String,
    val licence: String,
    val licenceUrl: String,
    val notice: String? = null,
)

private const val APACHE_2 = "Apache License 2.0"
private const val APACHE_2_URL = "https://www.apache.org/licenses/LICENSE-2.0"

/**
 * The open-source libraries the game ships with, on any of its platforms, and each one's licence
 * (CLAUDE.md §8d, *About*), written by hand, no library for it: the game's own dependencies in
 * `gradle/libs.versions.toml` and what they bring with them into an app, as
 * `:app:androidApp:dependencies --configuration prodReleaseRuntimeClasspath` and
 * `:app:desktopApp:dependencies --configuration runtimeClasspath` list them, and Skia, which Skiko
 * builds into the desktop, iOS and web apps. A library added to the game's client is added here in the
 * same change, under its own licence.
 */
val OPEN_SOURCE_LIBRARIES: List<Licensed> =
    listOf(
        "Kotlin",
        "kotlinx.coroutines",
        "kotlinx.serialization",
        "kotlinx-datetime",
        "kotlinx-io",
        "atomicfu",
        "Compose Multiplatform",
        "Skiko",
        "AndroidX",
        "Ktor",
        "OkHttp",
        "Okio",
        "Koin",
        "Stately",
        "Kotlin Wrappers",
        "JetBrains Runtime API",
        "JetBrains Java Annotations",
        "JSpecify",
        "Guava ListenableFuture",
        // Pushes on Android (CLAUDE.md §8a, *Push tokens*): firebase-messaging and what it brings, all of
        // the Firebase Android SDK, its data transport included, and the annotations those use.
        "Firebase Android SDK",
        "Error Prone annotations",
        "javax.inject",
    ).map { name -> Licensed(name, APACHE_2, APACHE_2_URL) } +
        listOf(
            Licensed(
                name = "SLF4J API",
                licence = "MIT License",
                licenceUrl = "https://www.slf4j.org/license.html",
                notice = "Copyright (c) 2004-2022 QOS.ch Sarl (Switzerland)",
            ),
            Licensed(
                name = "Skia (in Skiko)",
                licence = "BSD 3-Clause License",
                licenceUrl = "https://skia.googlesource.com/skia/+/main/LICENSE",
                notice = "Copyright (c) 2011 Google Inc.",
            ),
        )

/** What the About screen says the game is for, the store listing's and the terms' age (CLAUDE.md §8b). */
const val AGE_RATING: String = "16+"
