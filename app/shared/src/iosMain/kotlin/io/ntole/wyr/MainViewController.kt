package io.ntole.wyr

import androidx.compose.ui.window.ComposeUIViewController
import io.ntole.wyr.analytics.AnalyticsSettings
import io.ntole.wyr.di.initKoin
import org.koin.mp.KoinPlatform
import platform.Foundation.NSBundle

/**
 * iOS entry point, called from `iOSApp.swift`.
 *
 * SwiftUI may build this view controller more than once, and starting Koin twice throws — hence
 * the guard. The Android, desktop, and web entry points each run once per process and do not
 * need it.
 *
 * The name is PascalCase because Swift calls it as a view-controller factory; that is the iOS
 * convention here, not a style slip, so the naming rule is suppressed for this declaration only.
 */
@Suppress("ktlint:standard:function-naming")
fun MainViewController() =
    ComposeUIViewController {
        if (KoinPlatform.getKoinOrNull() == null) {
            initKoin(
                environmentName = bundledEnvironmentName(),
                analytics = bundledAnalyticsSettings(),
                build = bundledBuildNumber(),
            )
        }
        App()
    }

/**
 * The app's `WYR_ENV` Info.plist key, which the `WYR_ENV` build setting in `Config.xcconfig` fills in
 * (CLAUDE.md §8e), read from the main bundle by [environmentNameFrom].
 */
internal fun bundledEnvironmentName(): String? =
    environmentNameFrom { key -> NSBundle.mainBundle.objectForInfoDictionaryKey(key) }

/**
 * The name [lookup] gives for the `WYR_ENV` key, or `null` when it gives none, which [initKoin] reads
 * as local. A value that is not a string is handed on as its text, for [initKoin] to refuse by name.
 * Apart from the bundle so a test can give the key a value: the test binary's bundle has none.
 */
internal fun environmentNameFrom(lookup: (String) -> Any?): String? = lookup(ENVIRONMENT_KEY)?.toString()

/**
 * The analytics the app sends (CLAUDE.md §8g): the `WYR_POSTHOG_KEY` and `WYR_POSTHOG_HOST` Info.plist
 * keys, which the build settings of the same names fill in, from `Local.xcconfig`, and the app's
 * version, `CFBundleShortVersionString`, which Xcode makes from `MARKETING_VERSION`; read from the main
 * bundle by [analyticsSettingsFrom].
 */
internal fun bundledAnalyticsSettings(): AnalyticsSettings =
    analyticsSettingsFrom { key -> NSBundle.mainBundle.objectForInfoDictionaryKey(key) }

/**
 * The analytics [lookup] names: no key, or a blank one, is analytics off, which a build without the
 * settings is. Apart from the bundle, as [environmentNameFrom] is, so a test can give the keys values.
 */
internal fun analyticsSettingsFrom(lookup: (String) -> Any?): AnalyticsSettings =
    AnalyticsSettings(
        key = lookup(POSTHOG_KEY)?.toString(),
        host = lookup(POSTHOG_HOST)?.toString(),
        appVersion = lookup(APP_VERSION_KEY)?.toString().orEmpty(),
    )

/**
 * The app's build number, `CFBundleVersion`, which Xcode makes from `CURRENT_PROJECT_VERSION` in
 * `Config.xcconfig` (CLAUDE.md §8g, *The build number*), read from the main bundle by [buildNumberFrom].
 */
internal fun bundledBuildNumber(): Int? = buildNumberFrom { key -> NSBundle.mainBundle.objectForInfoDictionaryKey(key) }

/**
 * The build number [lookup] gives for `CFBundleVersion`, or null when it gives none that is a whole
 * number, and the app's requests then name no build. Apart from the bundle, as [environmentNameFrom] is.
 */
internal fun buildNumberFrom(lookup: (String) -> Any?): Int? =
    lookup(BUILD_NUMBER_KEY)?.toString()?.trim()?.toIntOrNull()

private const val ENVIRONMENT_KEY = "WYR_ENV"
private const val POSTHOG_KEY = "WYR_POSTHOG_KEY"
private const val POSTHOG_HOST = "WYR_POSTHOG_HOST"
private const val APP_VERSION_KEY = "CFBundleShortVersionString"
private const val BUILD_NUMBER_KEY = "CFBundleVersion"
