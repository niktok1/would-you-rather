package io.ntole.wyr.theme

import androidx.compose.ui.graphics.Color

/**
 * Every colour in the app, in one place (CLAUDE.md §5b).
 *
 * Screens read these through [LocalWyrColors]; no composable outside this package may write a
 * hex literal. The game's own look, free, is [WyrLightColors] and [WyrDarkColors], here; the shop's
 * themes are one [WyrColors] each, in `ShopThemes.kt` ([GameThemes], CLAUDE.md §8d, *The shop*).
 */
data class WyrColors(
    val pageBackground: Color,
    val surface: Color,
    val primaryText: Color,
    val headingAccent: Color,
    val muted: Color,
    val orPillText: Color,
    val orPillBackground: Color,
    val optionA: Color,
    val onOptionA: Color,
    val optionB: Color,
    val onOptionB: Color,
    /**
     * The track of the reveal's bar on each card (CLAUDE.md §8d, *The Play screen*): the card's own
     * colour, darker, a groove along its edge, which the bar fills in the card's text colour.
     */
    val revealTrackOnA: Color,
    val revealTrackOnB: Color,
    /** The coin the points are shown with (CLAUDE.md §5b): its face, and its rim and ring on it. */
    val coin: Color,
    val onCoin: Color,
    /**
     * A failure's words, on the page or a surface: a pink of the brand's, darker in the light theme and
     * lighter in the dark one than card A's, so it reads at AA (CLAUDE.md §5b). Material's `error`.
     */
    val error: Color,
    val isDark: Boolean,
)

/**
 * The two answer colours are the brand and stay constant in light and dark — they are how the
 * game is recognised. Everything else shifts with the mode. A theme bought in the shop gives the cards
 * colours of its own (CLAUDE.md §5b).
 */
private val OptionA = Color(0xFFD4537E)
private val OnOptionA = Color(0xFFFFFFFF)
private val OptionB = Color(0xFFEF9F27)
private val OnOptionB = Color(0xFF412402)

/** Each card's colour, darker: the reveal's bar's empty groove on each. Constant, as the cards are. */
private val RevealTrackOnA = Color(0xFFA83F63)
private val RevealTrackOnB = Color(0xFFC27C17)

/** A gold coin, in the brand's amber with its dark brown: the same in light and dark, as the cards. */
private val Coin = OptionB
private val OnCoin = OnOptionB

val WyrLightColors: WyrColors =
    WyrColors(
        pageBackground = Color(0xFFFFF7FA),
        surface = Color(0xFFFFFFFF),
        primaryText = Color(0xFF412402),
        headingAccent = Color(0xFF993556),
        // Darker than the dark theme's grey, which on this page read at 3.4 to 1, under AA (CLAUDE.md §5b).
        muted = Color(0xFF6F6E68),
        orPillText = Color(0xFF993556),
        orPillBackground = Color(0xFFFBEAF0),
        optionA = OptionA,
        onOptionA = OnOptionA,
        optionB = OptionB,
        onOptionB = OnOptionB,
        revealTrackOnA = RevealTrackOnA,
        revealTrackOnB = RevealTrackOnB,
        coin = Coin,
        onCoin = OnCoin,
        error = Color(0xFFB83A65),
        isDark = false,
    )

val WyrDarkColors: WyrColors =
    WyrColors(
        pageBackground = Color(0xFF161417),
        surface = Color(0xFF221F23),
        primaryText = Color(0xFFF3EDEF),
        headingAccent = Color(0xFFED93B1),
        muted = Color(0xFF888780),
        orPillText = Color(0xFFF4C0D1),
        orPillBackground = Color(0xFF3A2330),
        optionA = OptionA,
        onOptionA = OnOptionA,
        optionB = OptionB,
        onOptionB = OnOptionB,
        revealTrackOnA = RevealTrackOnA,
        revealTrackOnB = RevealTrackOnB,
        coin = Coin,
        onCoin = OnCoin,
        error = Color(0xFFEC7AA0),
        isDark = true,
    )
