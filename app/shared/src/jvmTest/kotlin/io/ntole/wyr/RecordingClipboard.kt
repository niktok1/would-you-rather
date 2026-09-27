@file:Suppress("DEPRECATION")

package io.ntole.wyr

import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString

/**
 * A clipboard that keeps what is copied to it, for a test to read, so no test writes to the machine's
 * own clipboard, which a headless machine may not have. Compose's older interface, as the About screen
 * copies through it.
 */
internal class RecordingClipboard : ClipboardManager {
    val copied = mutableListOf<String>()

    override fun setText(annotatedString: AnnotatedString) {
        copied += annotatedString.text
    }

    override fun getText(): AnnotatedString? = copied.lastOrNull()?.let(::AnnotatedString)
}
