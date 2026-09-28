package io.ntole.wyr.share

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.language.GOOGLE_PLAY
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.language.fill
import io.ntole.wyr.play.percentStyle
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale
import kotlin.math.roundToInt

/** How many pixels across a shared question's image is, whatever the screen that makes it: 1080 by 1350. */
const val SHARE_IMAGE_WIDTH_PX: Int = 1080

/**
 * [content], a shared question's image ([ShareCard]), laid out at `WyrDimens.shareCardWidth` by
 * `shareCardHeight` in a density of its own, so it is [SHARE_IMAGE_WIDTH_PX] across on every screen, and
 * recorded into [layer], which `GraphicsLayer.toImageBitmap` then turns into the image shared. On screen
 * it is shown shrunk to the width it is given, the preview of what goes.
 */
@Composable
internal fun ShareCardFrame(
    layer: GraphicsLayer,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val dimens = WyrThemeAccessors.dimens
    val density = Density(SHARE_IMAGE_WIDTH_PX / dimens.shareCardWidth.value, fontScale = 1f)
    val imageHeight = (SHARE_IMAGE_WIDTH_PX * dimens.shareCardHeight.value / dimens.shareCardWidth.value).roundToInt()

    Layout(
        content = {
            CompositionLocalProvider(LocalDensity provides density) {
                Box(
                    modifier =
                        Modifier.drawWithContent {
                            layer.record { this@drawWithContent.drawContent() }
                            drawLayer(layer)
                        },
                ) {
                    content()
                }
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val image = measurables.single().measure(Constraints.fixed(SHARE_IMAGE_WIDTH_PX, imageHeight))
        val scale =
            if (constraints.hasBoundedWidth) minOf(1f, constraints.maxWidth / SHARE_IMAGE_WIDTH_PX.toFloat()) else 1f
        layout((SHARE_IMAGE_WIDTH_PX * scale).roundToInt(), (imageHeight * scale).roundToInt()) {
            image.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

/**
 * A shared question's image (CLAUDE.md §8d, *Sharing*), on the page's background: the game's name, the
 * two options on cards of their colours (§5b) and where to play. [withResults], and the question has
 * them, each card shows its side's share and a bar filled to it, and the card the player picked says
 * so. Every colour, space and size from the theme, every word from [LocalStrings].
 */
@Composable
internal fun ShareCard(
    question: SharedQuestion,
    withResults: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val strings = LocalStrings.current
    val tally = question.tally?.takeIf { withResults }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dimens.spaceMd),
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.pageBackground)
                .padding(dimens.spaceLg),
    ) {
        Text(
            text = strings.gameName,
            color = colors.headingAccent,
            fontSize = WyrTypeScale.heading,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        SharedOption(
            text = question.optionA,
            background = colors.optionA,
            contentColor = colors.onOptionA,
            track = colors.revealTrackOnA,
            percent = tally?.percentA,
            picked = tally != null && question.pick == Side.A,
        )
        SharedOption(
            text = question.optionB,
            background = colors.optionB,
            contentColor = colors.onOptionB,
            track = colors.revealTrackOnB,
            percent = tally?.percentB,
            picked = tally != null && question.pick == Side.B,
        )
        Text(
            text =
                strings.playScreen.share.invite
                    .fill(GOOGLE_PLAY),
            color = colors.muted,
            fontSize = WyrTypeScale.statLabel,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * One option on its card, as large as it fits; with its side's [percent] under it and a bar along the
 * card's bottom filled to it, and [picked], *My pick* on a pill astride the card's top edge, so it takes
 * none of the option's room: in the page's text colour, which reads over the card and the page alike.
 */
@Composable
private fun ColumnScope.SharedOption(
    text: String,
    background: Color,
    contentColor: Color,
    track: Color,
    percent: Int?,
    picked: Boolean,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens

    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
        SharedOptionCard(text, background, contentColor, track, percent)
        if (picked) {
            Text(
                text = LocalStrings.current.playScreen.share.myPick,
                color = colors.pageBackground,
                fontSize = WyrTypeScale.statLabel,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        // Half over the card's top edge, half over what is above it.
                        .layout { measurable, constraints ->
                            val pill = measurable.measure(constraints)
                            layout(pill.width, pill.height) { pill.place(0, -pill.height / 2) }
                        }.background(colors.primaryText, CircleShape)
                        .padding(horizontal = dimens.spaceSm, vertical = dimens.spaceXs),
            )
        }
    }
}

/** The card of [SharedOption], filling the room it is given. */
@Composable
private fun SharedOptionCard(
    text: String,
    background: Color,
    contentColor: Color,
    track: Color,
    percent: Int?,
) {
    val dimens = WyrThemeAccessors.dimens

    Surface(
        shape = RoundedCornerShape(dimens.radiusCard),
        color = background,
        contentColor = contentColor,
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(dimens.spaceSm, Alignment.CenterVertically),
                modifier = Modifier.fillMaxSize().padding(dimens.spaceMd),
            ) {
                Text(
                    text = text,
                    fontSize = WyrTypeScale.optionText,
                    lineHeight = WyrTypeScale.optionLineHeight,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    autoSize = SharedOptionAutoSize,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (percent != null) {
                    Text(
                        text = LocalStrings.current.playScreen.percent(percent),
                        style = percentStyle(),
                        maxLines = 1,
                    )
                }
            }
            if (percent != null) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(dimens.revealBarHeight)
                            .background(track),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(percent / 100f)
                                .background(contentColor),
                    )
                }
            }
        }
    }
}

/** An option on the image, as the Play screen's cards fit theirs: the largest step that fits whole. */
private val SharedOptionAutoSize: TextAutoSize =
    TextAutoSize.StepBased(
        minFontSize = WyrTypeScale.optionTextMin,
        maxFontSize = WyrTypeScale.optionText,
        stepSize = WyrTypeScale.optionTextStep,
    )
