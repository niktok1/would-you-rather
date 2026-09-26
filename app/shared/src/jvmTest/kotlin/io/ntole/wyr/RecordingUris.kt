package io.ntole.wyr

import androidx.compose.ui.platform.UriHandler

/**
 * Stands in for the platform's way to open a link, which on the desktop is the machine's own browser:
 * every URL a screen asks to open is kept in [opened], and nothing leaves the test. Unless it [opens]
 * them, it then throws as Android's does when no app takes the link (a phone with no browser).
 */
internal class RecordingUris(
    private val opens: Boolean = true,
) : UriHandler {
    val opened = mutableListOf<String>()

    override fun openUri(uri: String) {
        opened += uri
        if (!opens) throw IllegalArgumentException("Can't open $uri.")
    }
}
