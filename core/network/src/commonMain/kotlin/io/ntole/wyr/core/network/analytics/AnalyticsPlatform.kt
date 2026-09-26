package io.ntole.wyr.core.network.analytics

/**
 * What every event says of the device it came from (CLAUDE.md §8g): [name], the game's platform
 * (`android`, `ios`, `desktop` or `web`), and the OS as PostHog names them in its own `$os`,
 * `$os_version` and `$device_type`. Nothing that tells one device from another.
 */
internal class AnalyticsPlatform(
    val name: String,
    val os: String,
    val osVersion: String,
    val deviceType: String,
)

/** This platform's, read once. */
internal expect fun analyticsPlatform(): AnalyticsPlatform

internal const val MOBILE: String = "Mobile"
internal const val DESKTOP: String = "Desktop"

/**
 * The OS and the kind of device a browser's [userAgent] names, as PostHog's own web SDK names them,
 * for a page, which has no OS of its own to ask: a phone's browser is a mobile, anything else a
 * desktop. An agent it cannot read is `Unknown`.
 */
internal fun osOfUserAgent(userAgent: String): Pair<String, String> =
    when {
        "Android" in userAgent -> "Android" to MOBILE
        listOf("iPhone", "iPad", "iPod").any { it in userAgent } -> "iOS" to MOBILE
        "CrOS" in userAgent -> "Chrome OS" to DESKTOP
        "Mac OS X" in userAgent || "Macintosh" in userAgent -> "Mac OS X" to DESKTOP
        "Windows" in userAgent -> "Windows" to DESKTOP
        "Linux" in userAgent -> "Linux" to DESKTOP
        else -> "Unknown" to DESKTOP
    }

/** The OS a JVM's `os.name` names, as PostHog names it. */
internal fun osOfJvmName(osName: String): String =
    when {
        osName.startsWith("Mac") -> "Mac OS X"
        osName.startsWith("Windows") -> "Windows"
        osName.startsWith("Linux") -> "Linux"
        else -> osName
    }
