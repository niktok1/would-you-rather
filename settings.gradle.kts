rootProject.name = "WYR"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Wire contract. Shared by :server and the client layers.
include(":core")

include(":server")

/**
 * The server image builds only `:server` and `:core`.
 *
 * The Android Gradle plugin needs an SDK at *configuration* time, so merely declaring the app
 * modules would fail a Docker build that has no SDK and no `local.properties`. Skipping them
 * changes nothing about how `:server` itself is built.
 */
val serverOnly =
    providers.environmentVariable("WYR_SERVER_ONLY").isPresent ||
        providers.gradleProperty("wyr.serverOnly").isPresent

if (!serverOnly) {
    // Client layers. Dependencies point inward: :core:domain depends on nothing.
    include(":core:domain")
    include(":core:network")
    include(":core:data")

    // Platform entry points + shared Compose UI (CLAUDE.md §3)
    include(":app:androidApp")
    include(":app:desktopApp")
    include(":app:shared")
    include(":app:webApp")

    // The moderation app, desktop and browser, on the client layers alone (CLAUDE.md §3).
    include(":app:adminApp")
}
