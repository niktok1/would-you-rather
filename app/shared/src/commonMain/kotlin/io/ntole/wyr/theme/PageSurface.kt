package io.ntole.wyr.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Whether the page is drawn already, under the whole window, by `App`: then a screen's [PageSurface]
 * draws nothing of its own, so the theme's art spans the window rather than each screen's box.
 */
val LocalPageDrawn = staticCompositionLocalOf { false }

/**
 * A screen's page: the theme's page colour, the theme's art drawn on it ([LocalThemeArt], CLAUDE.md
 * §8d, *The shop*), and [content] over both in the theme's text colour. In the app the page is drawn
 * once under the whole window ([LocalPageDrawn]) and this draws only [content]; a screen drawn on its
 * own, as a test draws one, draws its page itself.
 */
@Composable
fun PageSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = WyrThemeAccessors.colors
    val art = LocalThemeArt.current
    val page =
        if (LocalPageDrawn.current) {
            modifier
        } else {
            modifier.background(colors.pageBackground).drawBehind { drawThemeArt(art) }
        }
    Surface(color = Color.Transparent, contentColor = colors.primaryText, modifier = page, content = content)
}

/**
 * The page under the whole window, as `App` draws it once under every screen: the theme's page colour
 * and its art ([LocalThemeArt]), in a layer of its own, so it is recorded once and drawn again from that
 * record whatever moves over it, a screen's change above all, until its size or the theme changes. It
 * says nothing to a screen reader. [drawn], for a test, is told each time it is recorded anew.
 */
@Composable
internal fun WholePageArt(
    modifier: Modifier = Modifier,
    drawn: () -> Unit = {},
) {
    val page = WyrThemeAccessors.colors.pageBackground
    val art = LocalThemeArt.current
    Spacer(
        modifier =
            modifier.graphicsLayer().drawBehind {
                drawn()
                drawRect(page)
                drawThemeArt(art)
            },
    )
}
