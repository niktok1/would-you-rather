package io.ntole.wyr.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.language.fill
import io.ntole.wyr.points.CoinIcon
import io.ntole.wyr.theme.GameTheme
import io.ntole.wyr.theme.WyrIcons
import io.ntole.wyr.theme.WyrTheme
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale
import io.ntole.wyr.theme.drawThemeArt

/**
 * A small Play screen in [theme] (CLAUDE.md §8d, *The shop*), so a player sees what a theme is before
 * they buy it: its page and its art, its two cards with a question on them, and the row between them,
 * the points and the thumbs. Drawn in the theme's own tokens, whatever the game wears, [height] high,
 * [large] in the dialog before a purchase, [compact] on a tile of the player's own themes. A screen reader hears it as one picture, named for the theme.
 */
@Composable
fun ThemePreview(
    theme: GameTheme,
    height: Dp,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    compact: Boolean = false,
) {
    val strings = LocalStrings.current.shopScreen
    val name = strings.preview.fill(themeName(theme.id, strings))

    WyrTheme(theme = theme) {
        val colors = WyrThemeAccessors.colors
        val dimens = WyrThemeAccessors.dimens
        val art = theme.art
        val optionSize =
            when {
                large -> WyrTypeScale.previewOptionLarge
                compact -> WyrTypeScale.previewOptionSmall
                else -> WyrTypeScale.previewOption
            }
        // A tile is narrow, so its cards keep less off the sides; a card and the dialog show more art.
        val side = if (compact) dimens.spaceSm else dimens.spaceXl

        Box(
            modifier =
                modifier
                    .fillMaxWidth()
                    .height(height)
                    .clip(RoundedCornerShape(dimens.radiusPreview))
                    .background(colors.pageBackground)
                    .drawBehind { drawThemeArt(art) }
                    .clearAndSetSemantics { contentDescription = name },
        ) {
            // Inset from the sides and clear of the bottom, where every theme's art lies, so it shows.
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(start = side, end = side, top = dimens.spaceSm)
                        .padding(bottom = height * if (compact) ART_SHOWN_ON_TILE else ART_SHOWN),
                verticalArrangement = Arrangement.spacedBy(dimens.spaceXs),
            ) {
                PreviewCard(strings.previewOptionA, colors.optionA, colors.onOptionA, optionSize)
                // A tile is too small for the row: its cards alone.
                if (!compact) PreviewRow()
                PreviewCard(strings.previewOptionB, colors.optionB, colors.onOptionB, optionSize)
            }
        }
    }
}

/** One of the preview's cards, sharing what the row leaves with the other. */
@Composable
private fun ColumnScope.PreviewCard(
    text: String,
    color: Color,
    onColor: Color,
    size: TextUnit,
) {
    val dimens = WyrThemeAccessors.dimens
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(dimens.radiusPreview))
                .background(color),
    ) {
        Text(text = text, color = onColor, fontSize = size, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

/** The preview's row: the points on the left, and the thumbs in the middle, as on the Play screen. */
@Composable
private fun PreviewRow() {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val icon = dimens.previewIconSize

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        CoinIcon(size = icon)
        Spacer(Modifier.size(dimens.spaceXs))
        Text(text = PREVIEW_POINTS, color = colors.primaryText, fontSize = WyrTypeScale.previewRow)
        Spacer(Modifier.weight(1f))
        Icon(
            WyrIcons.ThumbUpFilled,
            contentDescription = null,
            tint = colors.headingAccent,
            modifier = Modifier.size(icon),
        )
        Spacer(Modifier.size(dimens.spaceSm))
        Icon(WyrIcons.ThumbDown, contentDescription = null, tint = colors.headingAccent, modifier = Modifier.size(icon))
        Spacer(Modifier.weight(1f))
        // As wide as the points, so the thumbs stand in the middle.
        Spacer(Modifier.size(icon + dimens.spaceXs))
        Text(text = PREVIEW_POINTS, color = Color.Transparent, fontSize = WyrTypeScale.previewRow)
    }
}

/** How much of the preview's height, at its bottom, is left to the theme's art alone. */
private const val ART_SHOWN = 0.24f

/** The same on a tile, whose cards need more of its little height. */
private const val ART_SHOWN_ON_TILE = 0.16f

/** The points the preview's row shows: a number, the same in every language. */
private const val PREVIEW_POINTS = "220"
