package io.ntole.wyr.analytics

/**
 * The PostHog project a build sends its analytics to (CLAUDE.md §8g), as the platform's entry point
 * reads it from the build: [key], none or blank being analytics off, [host], none being PostHog's EU
 * cloud, and the app's [appVersion], which every event names.
 *
 * Plain strings, as the environment's name is, so that `:core:network` stays off the entry points'
 * classpaths; [io.ntole.wyr.di.initKoin] makes them a configuration, and stops the app naming a host
 * that is none.
 */
data class AnalyticsSettings(
    val key: String?,
    val host: String?,
    val appVersion: String,
) {
    /** The key is left out: it is the user's project's, and no log needs it. */
    override fun toString(): String =
        "AnalyticsSettings(key=${if (key.isNullOrBlank()) "none" else "set"}, host=$host, appVersion=$appVersion)"
}
