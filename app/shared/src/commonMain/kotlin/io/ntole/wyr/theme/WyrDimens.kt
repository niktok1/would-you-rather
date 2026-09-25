package io.ntole.wyr.theme

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
    /** The outline of the answer card the player picked, once the answer is revealed. */
    val pickBorder: Dp = 4.dp,
    /**
     * The widest the middle of the Play screen's row between the cards may be, the points or how a
     * like failed, so the like count on its right is never cut short at 375 wide.
     */
    val playRowMiddleMaxWidth: Dp = 120.dp,
    val screenPadding: Dp = 20.dp,
    /**
     * The top bar's height, the tab row's before it, so a screen under it keeps the 599 of an iPhone
     * SE's 667 that its draw test holds it to (667 less the status bar's 20 and this).
     */
    val topBarHeight: Dp = 48.dp,
    val playButtonWidth: Dp = 240.dp,
    val playButtonHeight: Dp = 64.dp,
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
    val gameName = 40.sp

    /** The game's name's line, set with it: it takes two lines on a phone, and the text style's own is for body text. */
    val gameNameLineHeight = 46.sp
    val playButton = 24.sp
}
