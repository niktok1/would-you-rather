package io.ntole.wyr.analytics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Runs [onShown] each time the screen calling it is composed, as `LaunchedEffect(key)` would, and tells
 * it whether that begins a visit (CLAUDE.md §8g). A screen opened, from another screen or again after it
 * was left, begins one. A composition made anew that restores the screen, as an Android rotation makes
 * one, shows the visit it showed, which the analytics must not count twice: the screen still reads what
 * it shows again, but reports no second opening.
 *
 * Kept in saved state, which a screen left takes out of the composition with it, and which a rotation's
 * composition gets back.
 */
@Composable
fun ShownEffect(
    key: Any?,
    onShown: (newVisit: Boolean) -> Unit,
) {
    var begun by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(key) {
        onShown(!begun)
        begun = true
    }
}
