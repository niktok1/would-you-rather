package io.ntole.wyr.points

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.language.fill
import io.ntole.wyr.theme.WyrIcons
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale

// The points, wherever the game shows them (CLAUDE.md §8f, *Numbers and symbols*): a gold coin and
// the number, the coin in place of the unit a letter once was. A screen reader hears them in words,
// *Поени: 43* (Strings.points), since a coin says nothing to it.

/**
 * A gold coin, [size] across its box: [WyrIcons.CoinFace] in [WyrColors][io.ntole.wyr.theme.WyrColors]'
 * coin colour under [WyrIcons.CoinMark] in the colour on it, each icon tinted whole as every icon is.
 * Says nothing to a screen reader: what the points are is said beside it.
 */
@Composable
fun CoinIcon(
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(size)) { CoinLayers() }
}

/** The coin's two icons, filling whatever box they are drawn in. */
@Composable
private fun CoinLayers() {
    val colors = WyrThemeAccessors.colors
    Icon(WyrIcons.CoinFace, contentDescription = null, tint = colors.coin, modifier = Modifier.fillMaxSize())
    Icon(WyrIcons.CoinMark, contentDescription = null, tint = colors.onCoin, modifier = Modifier.fillMaxSize())
}

/**
 * [points] as the game shows an amount on its own, on the Play screen's row, the Account card and the
 * shop: the coin and the number, the coin as tall as the number's text and a little more. A screen
 * reader hears *Поени: 43*, in the language shown. With [onClick] it is a button, the way to the shop
 * where the points are spent (CLAUDE.md §8d, *The shop*), heard as the same words and a button.
 */
@Composable
fun PointsAmount(
    points: Int,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
) {
    val dimens = WyrThemeAccessors.dimens
    val label = LocalStrings.current.points.fill(points)
    // The size the number is drawn at, in sp, which a coin in dp can be made from: the theme's body
    // size where neither says.
    val textSize =
        listOf(fontSize, LocalTextStyle.current.fontSize).firstOrNull { it.isSp } ?: WyrTypeScale.sectionTitle
    val coin = with(LocalDensity.current) { (textSize * COIN_TO_TEXT).toDp() }

    val tappable = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.then(tappable).clearAndSetSemantics { contentDescription = label },
    ) {
        CoinIcon(size = coin)
        Spacer(Modifier.size(dimens.spaceXs))
        Text(text = points.toString(), fontSize = fontSize, fontWeight = fontWeight, color = color, maxLines = 1)
    }
}

/**
 * [template] with [points] in place of its `{0}`, as the coin and the number, in running text: the
 * Auth page's warning and the Submit form's button. A screen reader hears [spoken] instead, the same
 * sentence in words.
 */
@Composable
fun PointsText(
    template: String,
    points: Int,
    spoken: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    textAlign: TextAlign? = null,
) {
    val (before, after) = template.split(PLACEHOLDER, limit = 2).let { it.first() to it.getOrElse(1) { "" } }
    val text =
        buildAnnotatedString {
            append(before)
            appendInlineContent(COIN)
            // A narrow no-break space: the coin and its number never part at a line's end.
            append(" $points")
            append(after)
        }
    val coin =
        InlineTextContent(
            Placeholder(COIN_TO_TEXT.em, COIN_TO_TEXT.em, PlaceholderVerticalAlign.TextCenter),
        ) { Box(Modifier.fillMaxSize()) { CoinLayers() } }

    Text(
        text = text,
        inlineContent = mapOf(COIN to coin),
        color = color,
        style = style,
        textAlign = textAlign,
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
    )
}

/** How tall the coin is beside its number, in the number's text size. */
private const val COIN_TO_TEXT = 1.2f

private const val COIN = "coin"

private const val PLACEHOLDER = "{0}"
