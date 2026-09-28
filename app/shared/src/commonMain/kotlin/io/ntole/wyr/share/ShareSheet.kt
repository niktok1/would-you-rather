package io.ntole.wyr.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap

/**
 * The platform's way to share a question's image and a message with it (CLAUDE.md §8d, *Sharing*):
 * Android's share sheet, iOS's share sheet, a browser's Web Share, or the desktop's clipboard. It
 * never throws: what it did, or that nothing could, is its [ShareOutcome].
 */
fun interface ShareSheet {
    suspend fun share(
        image: ImageBitmap,
        text: String,
    ): ShareOutcome
}

/** What a [ShareSheet] did with a question's image. */
enum class ShareOutcome {
    /** The platform's share sheet opened, which is the player's from then on. */
    OPENED,

    /** The image and the message are on the clipboard: the desktop's. */
    COPIED,

    /** The image was downloaded and the message copied: a browser that cannot share a file. */
    SAVED,

    /** Nothing could share it. */
    FAILED,
}

/**
 * The app's [ShareSheet], which `App` provides ([rememberShareSheet]); none, where nothing provides one,
 * a screen drawn alone, shares nothing.
 */
val LocalShareSheet = staticCompositionLocalOf { ShareSheet { _, _ -> ShareOutcome.FAILED } }

/** This platform's [ShareSheet]. */
@Composable
internal expect fun rememberShareSheet(): ShareSheet

/**
 * The game's page in the Play Store, which a shared question's message links to: the `prod` flavor's,
 * the only one with a store page, whichever build shares. A link to the question itself comes with the
 * site's domain (CLAUDE.md §8d, *Sharing*).
 */
const val STORE_URL: String = "https://play.google.com/store/apps/details?id=io.ntole.wyr"
