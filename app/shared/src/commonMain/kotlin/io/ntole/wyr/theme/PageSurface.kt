package io.ntole.wyr.theme

import androidx.compose.foundation.background
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color

/**
 * A screen's page: the theme's page colour, the theme's art drawn on it ([LocalThemeArt], CLAUDE.md
 * §8d, *The shop*), and [content] over both in the theme's text colour. Every screen of the game stands
 * on one, so a theme's art is behind each; the game's own theme draws none.
 */
@Composable
fun PageSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = WyrThemeAccessors.colors
    val art = LocalThemeArt.current
    Surface(
        color = Color.Transparent,
        contentColor = colors.primaryText,
        modifier = modifier.background(colors.pageBackground).drawBehind { drawThemeArt(art) },
        content = content,
    )
}
