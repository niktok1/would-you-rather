package io.ntole.wyr.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android's share sheet: the image written as a PNG to the app's cache, handed out through the app's
 * `FileProvider` (`${applicationId}.share`, in `:app:androidApp`'s manifest, over `res/xml/share_paths.xml`)
 * with the message beside it, to whichever app the player picks.
 */
@Composable
internal actual fun rememberShareSheet(): ShareSheet {
    val context = LocalContext.current
    return remember(context) { ShareSheet { image, text -> share(context, image, text) } }
}

private suspend fun share(
    context: Context,
    image: ImageBitmap,
    text: String,
): ShareOutcome =
    try {
        val file = withContext(Dispatchers.IO) { writePng(context, image) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        val send =
            Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TEXT, text)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The chooser shows the image too, which it may read only through the clip's grant.
        send.clipData = ClipData.newRawUri(null, uri)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ShareOutcome.OPENED
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Log.w(TAG, "sharing a question failed: ${failure::class.simpleName}")
        ShareOutcome.FAILED
    }

/** [image] as `shared/question.png` in the cache, the one before replaced. */
private fun writePng(
    context: Context,
    image: ImageBitmap,
): File {
    val folder = File(context.cacheDir, SHARED_FOLDER).apply { mkdirs() }
    val file = File(folder, "question.png")
    // A layer's image may be a hardware bitmap, whose pixels cannot be read to compress.
    val bitmap =
        image.asAndroidBitmap().let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && it.config == Bitmap.Config.HARDWARE) {
                it.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                it
            }
        }
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    return file
}

/** The cache's folder the provider hands out, `share_paths.xml`'s `cache-path`. */
private const val SHARED_FOLDER = "shared"

/** Ignored for a PNG, which is lossless. */
private const val PNG_QUALITY = 100

private const val TAG = "WyrShare"
