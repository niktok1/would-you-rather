package io.ntole.wyr

import androidx.compose.ui.window.ComposeUIViewController
import io.ntole.wyr.di.initKoin
import org.koin.core.context.GlobalContext

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
        if (GlobalContext.getOrNull() == null) {
            initKoin()
        }
        App()
    }
