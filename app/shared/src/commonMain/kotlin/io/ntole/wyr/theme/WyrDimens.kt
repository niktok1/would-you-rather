package io.ntole.wyr.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Spacing and radius scale. Same rule as [WyrColors]: no raw `dp` literals in screen code.
 */
data class WyrDimens(
    val spaceXs: Dp = 4.dp,
    val spaceSm: Dp = 8.dp,
    val spaceMd: Dp = 16.dp,
    val spaceLg: Dp = 24.dp,
    val spaceXl: Dp = 32.dp,
    val radiusCard: Dp = 24.dp,
    val radiusPill: Dp = 999.dp,
    val optionMinHeight: Dp = 140.dp,
    val screenPadding: Dp = 20.dp,
)

val WyrDefaultDimens: WyrDimens = WyrDimens()

/** Type sizes that Material's scale does not cover well for this layout. */
object WyrTypeScale {
    val optionText = 22.sp
    val orPill = 14.sp
    val heading = 28.sp
    val sectionTitle = 16.sp
    val statLabel = 13.sp
    val percentage = 34.sp

    /** Raw values, log lines and HTTP trace lines on the dev console, where columns should align. */
    val code = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
}
