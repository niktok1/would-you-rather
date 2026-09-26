package io.ntole.wyr

import androidx.compose.ui.platform.UriHandler

/**
 * Stands in for the platform's way to open a link, which on the desktop is the machine's own browser:
 * every URL a screen asks to open is kept in [opened], and nothing leaves the test.
 */
internal class RecordingUris : UriHandler {
    val opened = mutableListOf<String>()

    override fun openUri(uri: String) {
        opened += uri
    }
}
