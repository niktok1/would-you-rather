package io.ntole.wyr.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController

/**
 * iOS's share sheet (`UIActivityViewController`) over the view controller on top, with the image and the
 * message; on an iPad a popover from the middle of the screen, which it asks for.
 */
@Composable
internal actual fun rememberShareSheet(): ShareSheet =
    remember {
        ShareSheet { image, text -> share(image, text) }
    }

@OptIn(ExperimentalForeignApi::class)
private fun share(
    image: ImageBitmap,
    text: String,
): ShareOutcome {
    val png = pngOf(image) ?: return ShareOutcome.FAILED
    val picture = UIImage.imageWithData(png) ?: return ShareOutcome.FAILED
    val top = topViewController() ?: return ShareOutcome.FAILED
    val sheet = UIActivityViewController(activityItems = listOf(picture, text), applicationActivities = null)
    sheet.popoverPresentationController?.let { popover ->
        popover.sourceView = top.view
        top.view.bounds.useContents { popover.sourceRect = CGRectMake(size.width / 2, size.height / 2, 0.0, 0.0) }
    }
    top.presentViewController(sheet, animated = true, completion = null)
    return ShareOutcome.OPENED
}

/** [image] as a PNG, through Skia, which draws Compose on iOS. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun pngOf(image: ImageBitmap): NSData? {
    val bytes = Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes ?: return null
    return bytes.usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong()) }
}

/** The view controller on top of the key window's, the one a sheet is presented over. */
private fun topViewController(): UIViewController? {
    @Suppress("DEPRECATION")
    var top = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}
