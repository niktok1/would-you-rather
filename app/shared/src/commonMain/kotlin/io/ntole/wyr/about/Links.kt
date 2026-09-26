package io.ntole.wyr.about

import androidx.compose.ui.platform.UriHandler

/**
 * Opens the first of [uris] something on the device opens, through [open], which throws when nothing
 * can: Compose's [UriHandler] an `IllegalArgumentException` on Android, and an `IOException` or an
 * `UnsupportedOperationException` on the desktop, Android's `startActivity` an
 * `ActivityNotFoundException`. When nothing opens any of them it does nothing, so a phone with no
 * browser, a managed profile's say, keeps the game open (CLAUDE.md §8d, *About*).
 */
internal fun openFirst(
    uris: List<String>,
    open: (String) -> Unit,
) {
    uris.firstOrNull { uri -> opens(uri, open) }
}

/** Opens [uri] in the browser, or does nothing when nothing on the device can ([openFirst]). */
internal fun UriHandler.openIfAble(uri: String) {
    openFirst(listOf(uri), ::openUri)
}

private fun opens(
    uri: String,
    open: (String) -> Unit,
): Boolean =
    try {
        open(uri)
        true
    } catch (nothingOpensIt: Exception) {
        false
    }
