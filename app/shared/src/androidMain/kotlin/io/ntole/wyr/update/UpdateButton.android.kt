package io.ntole.wyr.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The game's page in the Play Store app, `market://`, or on the web when no app takes it (a phone
 * without the Play Store). The application id is this build's own, flavor's suffix included, which only
 * the `prod` flavor's has a store page for.
 */
@Composable
internal actual fun rememberUpdateButton(): UpdateButton? {
    val context = LocalContext.current
    return remember(context) { UpdateButton(UpdateWay.STORE) { openStorePage(context) } }
}

private fun openStorePage(context: Context) {
    val id = context.packageName
    try {
        context.startActivity(view("market://details?id=$id"))
    } catch (noStore: ActivityNotFoundException) {
        context.startActivity(view("https://play.google.com/store/apps/details?id=$id"))
    }
}

private fun view(uri: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
