package io.ntole.wyr.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every colour pair the theme puts text or an icon on, in both themes, held to WCAG AA contrast
 * (CLAUDE.md §5b): 4.5 to 1 for text, 3 to 1 for large text (18.66 or more bold, 24 or more otherwise)
 * and for an icon or a graphic's edge. Computed from the tokens, and from the Material scheme the theme
 * mirrors them into, as WCAG computes it, from each colour's relative luminance.
 */
class WyrContrastTest {
    @Test
    fun `text reads at 4 and a half to 1 on everything it is put on`() {
        THEMES.forEach { colors ->
            val scheme = materialSchemeOf(colors)
            listOf(
                Pair("primary text on the page", colors.primaryText to colors.pageBackground),
                Pair("primary text on a surface", colors.primaryText to colors.surface),
                Pair("the heading accent on the page", colors.headingAccent to colors.pageBackground),
                Pair("the heading accent on a surface", colors.headingAccent to colors.surface),
                Pair("muted text on the page", colors.muted to colors.pageBackground),
                Pair("muted text on a surface", colors.muted to colors.surface),
                Pair("the pill's text on the pill", colors.orPillText to colors.orPillBackground),
                Pair("a failure on the page", colors.error to colors.pageBackground),
                Pair("a failure on a surface", colors.error to colors.surface),
                // Card B's text, the option shrunk below large text on a long one (§8d, *The Play screen*).
                Pair("an option on card B", colors.onOptionB to colors.optionB),
                Pair("the coin's mark on its face", colors.onCoin to colors.coin),
                Pair("a button's text on it", scheme.onPrimary to scheme.primary),
                Pair("the background's text", scheme.onBackground to scheme.background),
                Pair("a surface's text", scheme.onSurface to scheme.surface),
                Pair("a surface variant's text", scheme.onSurfaceVariant to scheme.surfaceVariant),
                Pair("a field's label on the page", scheme.onSurfaceVariant to scheme.background),
                Pair("a field's label on a surface", scheme.onSurfaceVariant to scheme.surface),
                Pair("a field's failure on a surface", scheme.error to scheme.surface),
            ).forEach { (what, pair) -> assertAtLeast(TEXT, what, pair, colors) }
        }
    }

    /**
     * Card A's text is large at its own size, an option's 22 bold and the percentage's 34, and reads
     * at 3 to 1 there. An option shrunk for its length is not large, and white on card A reads at 3.9
     * to 1 only: the brand's colour stays until the user decides (CLAUDE.md §8b, *Card A's contrast*).
     */
    @Test
    fun `card A's large text reads at 3 to 1`() {
        THEMES.forEach { colors ->
            assertAtLeast(LARGE_TEXT, "an option on card A", colors.onOptionA to colors.optionA, colors)
        }
    }

    @Test
    fun `icons and graphics stand out at 3 to 1`() {
        THEMES.forEach { colors ->
            listOf(
                Pair("an icon on the page", colors.headingAccent to colors.pageBackground),
                Pair("an icon on a surface", colors.headingAccent to colors.surface),
                Pair("card A's reveal bar", colors.onOptionA to colors.optionA),
                Pair("card B's reveal bar", colors.onOptionB to colors.optionB),
            ).forEach { (what, pair) -> assertAtLeast(GRAPHIC, what, pair, colors) }
            // The coin's edge is its face against the page, or its rim, drawn round the face, against it.
            val coin =
                max(contrast(colors.coin, colors.pageBackground), contrast(colors.onCoin, colors.pageBackground))
            assertTrue(coin >= GRAPHIC, "the coin on the page reads at $coin in ${themeOf(colors)}")
        }
    }

    private fun assertAtLeast(
        least: Float,
        what: String,
        pair: Pair<Color, Color>,
        colors: WyrColors,
    ) {
        val ratio = contrast(pair.first, pair.second)
        assertTrue(ratio >= least, "$what reads at $ratio to 1, under $least, in the ${themeOf(colors)} theme")
    }

    /** WCAG's contrast ratio of [a] and [b]: the lighter's luminance and 0.05, over the darker's and 0.05. */
    private fun contrast(
        a: Color,
        b: Color,
    ): Float {
        val (la, lb) = a.luminance() to b.luminance()
        return (max(la, lb) + FLARE) / (min(la, lb) + FLARE)
    }

    private fun themeOf(colors: WyrColors): String = if (colors.isDark) "dark" else "light"

    private companion object {
        val THEMES = listOf(WyrLightColors, WyrDarkColors)
        const val TEXT = 4.5f
        const val LARGE_TEXT = 3f
        const val GRAPHIC = 3f

        /** WCAG's allowance for the light a screen reflects, added to each luminance. */
        const val FLARE = 0.05f
    }
}
