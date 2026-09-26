package io.ntole.wyr.analytics

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
internal actual fun rememberConfigurationChanging(): () -> Boolean {
    val activity = LocalActivity.current
    // Asked as the activity stops, when Android has already said whether it is for a new configuration.
    return remember(activity) { { activity?.isChangingConfigurations == true } }
}
