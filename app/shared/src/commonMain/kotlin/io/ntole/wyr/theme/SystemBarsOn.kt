package io.ntole.wyr.theme

import androidx.compose.runtime.Composable

/**
 * Sets the system bars' icons to read on the page: light icons on a [darkPage], dark ones otherwise, so
 * a dark theme of the shop's worn on a phone in light mode keeps the clock and the battery legible.
 * Android's alone; elsewhere the page does not reach under the system's bars, or they set themselves.
 */
@Composable
expect fun SystemBarsOn(darkPage: Boolean)
