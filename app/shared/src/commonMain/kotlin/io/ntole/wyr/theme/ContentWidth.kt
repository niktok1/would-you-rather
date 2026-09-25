package io.ntole.wyr.theme

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * A screen's content no wider than [max], `WyrDimens.contentMaxWidth`, and centred in any width beyond
 * it (CLAUDE.md §8d, *Wide screens*): the whole width on a phone held upright, a column down the
 * middle of a phone on its side, a tablet or a desktop window. Placed after a scroll, it leaves the
 * whole width to scroll.
 */
fun Modifier.contentWidth(max: Dp): Modifier = wrapContentWidth().widthIn(max = max).fillMaxWidth()
