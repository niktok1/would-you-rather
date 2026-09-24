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
 * (CLAUDE.md §8e), or `null` when the bundle has none, which [initKoin] reads as local. A value that
 * is not a string is handed on as its text, for [initKoin] to refuse by name.
 */
internal fun bundledEnvironmentName(): String? =
    NSBundle.mainBundle.objectForInfoDictionaryKey(ENVIRONMENT_KEY)?.toString()

private const val ENVIRONMENT_KEY = "WYR_ENV"
