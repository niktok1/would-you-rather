package io.ntole.wyr.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException

/**
 * The desktop's share: no share sheet, so the image and the message go on the clipboard as one, to paste
 * into whatever takes them, an image where it takes one and the message where only text goes.
 */
@Composable
internal actual fun rememberShareSheet(): ShareSheet =
    remember {
        ShareSheet { image, text -> copy(image, text) }
    }

private fun copy(
    image: ImageBitmap,
    text: String,
): ShareOutcome =
    try {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(ImageAndText(image.toAwtImage(), text), null)
        ShareOutcome.COPIED
    } catch (_: Exception) {
        // No clipboard (a headless machine), or another app holds it.
        ShareOutcome.FAILED
    }

/** An image and a text on the clipboard together. */
private class ImageAndText(
    private val image: Image,
    private val text: String,
) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor, DataFlavor.stringFlavor)

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in transferDataFlavors

    override fun getTransferData(flavor: DataFlavor): Any =
        when (flavor) {
            DataFlavor.imageFlavor -> image
            DataFlavor.stringFlavor -> text
            else -> throw UnsupportedFlavorException(flavor)
        }
}
