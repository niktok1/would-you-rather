package io.ntole.wyr.navigation

import androidx.compose.runtime.Composable

/**
 * The platform's own back, where it has one, calling [onBack] while [enabled] (CLAUDE.md §8d,
 * *Navigation*). Android's back button and gesture go back through the [Navigator]; disabled, at
 * Home, they leave the app as they would without it. Desktop, the web and iOS have nothing to bind,
 * so their screens' own buttons are the way back.
 */
@Composable
expect fun SystemBack(
    enabled: Boolean,
    onBack: () -> Unit,
)
