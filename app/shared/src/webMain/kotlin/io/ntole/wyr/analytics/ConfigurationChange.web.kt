package io.ntole.wyr.analytics

import androidx.compose.runtime.Composable

@Composable
internal actual fun rememberConfigurationChanging(): () -> Boolean = { false }
