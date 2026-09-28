package io.ntole.wyr.theme

import androidx.compose.runtime.Composable

/** Nothing to set: the page does not reach under the system's bars here, or they set themselves. */
@Composable
actual fun SystemBarsOn(darkPage: Boolean) = Unit
