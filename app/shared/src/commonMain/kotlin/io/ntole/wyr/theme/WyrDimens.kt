package io.ntole.wyr.theme

import androidx.compose.ui.text.font.FontFamily
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
    /**
     * How high the answer card the player picked rises once the answer is revealed, its shadow's
     * elevation, easing back to none as the next question comes (CLAUDE.md §8d, *The Play screen*).
     */
    val pickElevation: Dp = 6.dp,
    /** The same on the dark page, where the lift is a glow of the card's own colour, which shows less. */
    val pickGlowElevation: Dp = 12.dp,
    /** The reveal's bar, flush along each card's edge by the row between them (CLAUDE.md §8d, *The Play screen*). */
    val revealBarHeight: Dp = 8.dp,
    /**
     * The widest the start of the Play screen's row may be, the points or how a reaction failed: four
     * digits of points at the row's largest font scale ([WyrTypeScale.PLAY_ROW_MAX_FONT_SCALE]).
     */
    val playRowStartMaxWidth: Dp = 100.dp,
    /**
     * The least an icon's slot in the Play screen's row narrows to, from a touch target's width, when the
     * row is tight (CLAUDE.md §8d, *The Play screen*): the icon's 24 and a little either side. The icon
     * button keeps a touch target's reach for taps all the same.
     */
    val playRowSlotMinWidth: Dp = 28.dp,
    /** An icon as Material draws it in an icon button, 24 square: where a thumb's count stands past it. */
    val iconSize: Dp = 24.dp,
    /** How far a thumb's count stands past the thumb's icon, inside the thumb's slot. */
    val reactionCountGap: Dp = 2.dp,
    /**
     * A shared question's image (CLAUDE.md §8d, *Sharing*), laid out at this size whatever the screen, 4
     * to 5, as a feed's post takes it, and drawn at `SHARE_IMAGE_WIDTH_PX` across; and the widest the
     * dialog that shows it before it goes.
     */
    val shareCardWidth: Dp = 360.dp,
    val shareCardHeight: Dp = 450.dp,
    val shareDialogMaxWidth: Dp = 360.dp,
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
    /**
     * The Home screen's two Play buttons, small and side by side (CLAUDE.md §8d, *Home picks*): the least
     * height of each, room for *Play*, its share and the reveal's bar, so the reveal moves nothing; and
     * the widest the pair stands together, centred on anything wider.
     */
    val homeButtonHeight: Dp = 128.dp,
    val homeButtonsMaxWidth: Dp = 328.dp,
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
    /**
     * A theme's preview in the shop (CLAUDE.md §8d, *The shop*): on its card, and larger in the dialog
     * before a purchase; its corners, and the coin on its row.
     */
    val themePreviewHeight: Dp = 150.dp,
    val themePreviewLargeHeight: Dp = 280.dp,
    val radiusPreview: Dp = 16.dp,
    val previewIconSize: Dp = 14.dp,
    /** A tile of the shop's picker of the player's own themes, its preview's height, and the worn one's outline. */
    val themeTileWidth: Dp = 108.dp,
    val themeTileHeight: Dp = 150.dp,
    val tileOutline: Dp = 3.dp,
    /** How far a screen slides as it comes in or goes (CLAUDE.md §5b, *Motion*), across. */
    val screenSlide: Dp = 24.dp,
    /** A question mark of the game's own theme's art at its own scale 1 (CLAUDE.md §5b, *Backgrounds*). */
    val backgroundMarkSize: Dp = 64.dp,
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
     * The most the Play screen's row's text grows with the phone's font size (CLAUDE.md §8d, *The Play
     * screen*): the points, the thumbs' counts and a reaction's failure, beside five icons that do not
     * grow at all. Past it, three-digit counts and four-digit points no longer fit 375 wide whatever the
     * spacing, so the row's text stays at this scale.
     */
    const val PLAY_ROW_MAX_FONT_SCALE = 1.3f

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

    /** An answer on a theme's preview in the shop, and the numbers on its row (CLAUDE.md §8d, *The shop*). */
    val previewOption = 13.sp
    val previewOptionLarge = 18.sp
    val previewOptionSmall = 11.sp
    val previewRow = 10.sp

    /** An id shown for the player to copy, the About screen's account id: fixed width, so it reads as a value. */
    val code = FontFamily.Monospace
}
