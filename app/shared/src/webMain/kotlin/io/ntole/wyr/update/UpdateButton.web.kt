package io.ntole.wyr.update

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import web.location.location

/** Loads the page again, which fetches the build the server serves now. */
@Composable
internal actual fun rememberUpdateButton(): UpdateButton? =
    remember {
        UpdateButton(UpdateWay.RELOAD) { location.reload() }
    }
