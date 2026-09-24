package io.ntole.wyr

import androidx.compose.ui.window.ComposeUIViewController
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
            initKoin(environmentName = bundledEnvironmentName())
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

private const val ENVIRONMENT_KEY = "WYR_ENV"
