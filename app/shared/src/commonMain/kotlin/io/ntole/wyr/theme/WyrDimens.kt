package io.ntole.wyr.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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
    val optionMinHeight: Dp = 140.dp,
    /** The outline of the answer card the player picked, once the answer is revealed. */
    val pickBorder: Dp = 4.dp,
    /** The reveal's bar along each card's edge by the row between them (CLAUDE.md §8d, *The Play screen*). */
    val revealBarHeight: Dp = 6.dp,
    /**
     * How far in from its card's edge the reveal's bar stands: past the pick's outline ([pickBorder]),
     * with a strip of the card's colour between, so the outline, in the bar's colour, never merges with it.
     */
    val revealBarInset: Dp = 8.dp,
    /**
     * The widest the start of the Play screen's row may be, the points or how a reaction failed, so the
     * thumbs stay in the middle and their counts and Skip are never cut short at 375 wide.
     */
    val playRowStartMaxWidth: Dp = 88.dp,
    /** The Account card's circle of the player's initial. */
    val avatarSize: Dp = 44.dp,
    /** Each number column of My questions' table: its likes, its dislikes and its answers. */
    val tableNumberWidth: Dp = 44.dp,
    /** The icons heading My questions' number columns, a little smaller than a button's. */
    val tableIconSize: Dp = 20.dp,
    val screenPadding: Dp = 20.dp,
    /**
     * The top bar's height, the tab row's before it, so a screen under it keeps the 599 of an iPhone
     * SE's 667 that its draw test holds it to (667 less the status bar's 20 and this).
     */
    val topBarHeight: Dp = 48.dp,
    val playButtonWidth: Dp = 240.dp,
    val playButtonHeight: Dp = 64.dp,
    /**
     * The least width, inside the screen's padding, at which the Play screen stands its cards side by
     * side over the row, when it is also wider than tall (CLAUDE.md §8d, *Wide screens*): a phone on
     * its side, a tablet on its side or a desktop window, never a phone held upright.
     */
    val wideLayoutMinWidth: Dp = 600.dp,
    /**
     * The widest the Account, Auth, Submit and Categories screens' content gets, centred on anything
     * wider (CLAUDE.md §8d, *Wide screens*), so fields and buttons do not stretch across a phone on its
     * side or a desktop window.
     */
    val contentMaxWidth: Dp = 600.dp,
)

val WyrDefaultDimens: WyrDimens = WyrDimens()

/** Type sizes that Material's scale does not cover well for this layout. */
object WyrTypeScale {
    /** An option on its card, as large as it fits, and never smaller than [optionTextMin]. */
    val optionText = 22.sp

    /**
     * The least an option too long for its card shrinks to, [optionTextStep] at a time from [optionText]
     * (CLAUDE.md §8d, *The Play screen*): a question's longest option, 200 characters, fits an iPhone SE's
     * card whole at it, revealed too.
     */
    val optionTextMin = 14.sp
    val optionTextStep = 2.sp

    /**
     * An option's line, in its own size, so it shrinks with it: at [optionText] it is the 24 the text
     * style's line was before the option shrank.
     */
    val optionLineHeight = 1.1.em
    val heading = 28.sp
    val sectionTitle = 16.sp
    val statLabel = 13.sp

    /**
     * The line of [statLabel] where it takes two lines, a failed like's on the Play screen: close
     * enough that two fit the heart's touch target up to half again the phone's font size.
     */
    val statLabelLineHeight = 16.sp
    val percentage = 34.sp
    val gameName = 40.sp

    /** The game's name's line, set with it: it takes two lines on a phone, and the text style's own is for body text. */
    val gameNameLineHeight = 46.sp
    val playButton = 24.sp
}
