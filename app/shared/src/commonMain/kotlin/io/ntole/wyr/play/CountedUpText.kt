package io.ntole.wyr.play

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import io.ntole.wyr.theme.WyrThemeAccessors
import kotlin.math.roundToInt

/**
 * How long the reveal's percentages take to count up from 0, both cards at once, and each card's bar
 * to fill with them (CLAUDE.md §8d, *The Play screen*). Internal, so a test can step the clock to it.
 */
internal const val COUNT_UP_MILLIS = 3_000

/**
 * The reveal's count up to [target], a share out of 100, as a race with [rival], the other side's share
 * (CLAUDE.md §8d, *The Play screen*): over [durationMillis], [COUNT_UP_MILLIS] unless a screen asks for
 * its own (the Home screen's, shorter), both sides climb together, slowing as they near the smaller
 * share, so which side is ahead shows only once the smaller one stops there; then the larger speeds on
 * to its own and eases into it. A card and the other count the same shared curve, each stopping at its
 * own share, so they move in step. One count for a card's percentage ([CountedUpText]) and its bar
 * ([RevealBar]) alike, so the two move as one; once per reveal.
 *
 * Read only where it is drawn, never composed (CLAUDE.md §8d, *The Play screen*): a frame of the count
 * draws the number and the bar again and does nothing else. Nothing is composed or laid out again, and
 * no semantics change reaches an accessibility service; a debug build, whose Compose runs several
 * times slower than a release build's, has no time for more at 60 frames a second.
 */
@Composable
internal fun rememberCountUp(
    target: Int,
    rival: Int = target,
    durationMillis: Int = COUNT_UP_MILLIS,
): State<Float> {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(target, rival) {
        progress.animateTo(1f, animationSpec = tween(durationMillis = durationMillis, easing = LinearEasing))
    }
    // Not a derived state: each read reads the animation itself, so whatever draws it is told of every frame.
    return remember(progress, target, rival) { RaceState(progress, target, rival) }
}

/** [raceAt] of [progress]'s value, read afresh on every read. */
private class RaceState(
    private val progress: Animatable<Float, *>,
    private val target: Int,
    private val rival: Int,
) : State<Float> {
    override val value: Float get() = raceAt(progress.value, target, rival)
}

/**
 * Where the race stands for [target] against [rival] at [progress], from 0 to 1 of the count's time:
 * both on the one curve, climbing together and slowing into the smaller share, then the larger speeding
 * on to its own; each capped at its own share. The climb together takes up to [TOGETHER_SHARE] of the
 * time, less the smaller the smaller share is (no less than [TOGETHER_MIN]), so a lopsided result does
 * not crawl; a tie climbs together the whole time. Internal, so a test can check the curve itself.
 */
internal fun raceAt(
    progress: Float,
    target: Int,
    rival: Int,
): Float {
    val low = minOf(target, rival).toFloat()
    val high = maxOf(target, rival).toFloat()
    val p = progress.coerceIn(0f, 1f)
    val together =
        if (high == low) 1f else TOGETHER_SHARE * (low / HALF).coerceIn(TOGETHER_MIN, 1f)
    val value =
        if (p < together || together >= 1f) {
            low * LinearOutSlowInEasing.transform(p / together)
        } else {
            low + (high - low) * FastOutSlowInEasing.transform((p - together) / (1f - together))
        }
    return minOf(value, target.toFloat())
}

/** How much of the count's time the two sides climb together at most, for a close result. */
private const val TOGETHER_SHARE = 0.72f

/** The least of that time, of [TOGETHER_SHARE], for a lopsided result. */
private const val TOGETHER_MIN = 0.28f

/** An even split, where the climb together takes all of [TOGETHER_SHARE]. */
private const val HALF = 50f

/**
 * [target] as the reveal shows it, worded by [text], in [style], at the whole number [counted] has
 * reached ([rememberCountUp]). The text alone counts; nothing moves.
 *
 * [target]'s own text is laid out once: it sizes the box, so nothing beside it moves as the count
 * widens, and it is what a screen reader reads, the share itself rather than a number on the way to
 * it. The number reached is drawn in its place, in the middle of it, and read only there.
 */
@Composable
internal fun CountedUpText(
    counted: State<Float>,
    target: Int,
    text: (Int) -> String,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    // The whole number reached: a frame that moves the count within one number draws nothing again.
    val reached = remember(counted) { derivedStateOf { counted.value.roundToInt() } }
    val measurer = rememberTextMeasurer()

    BasicText(
        text = text(target),
        style = style,
        modifier =
            modifier
                // A layer of its own, so a frame records this number again and not the card under it.
                .graphicsLayer()
                .drawWithContent {
                    // Not drawContent(): the target's text only sizes the box and is read out.
                    val number = measurer.measure(text(reached.value), style)
                    val x =
                        Alignment.CenterHorizontally.align(
                            number.size.width,
                            size.width.roundToInt(),
                            layoutDirection,
                        )
                    drawText(number, topLeft = Offset(x.toFloat(), 0f))
                },
    )
}

/**
 * A card's bar in the reveal (CLAUDE.md §8d, *The Play screen*): a [track] as wide as the box it is
 * laid in, filled in [fill] from its start as far as [counted] has reached, out of 100, so it fills
 * with the card's percentage and stops where the percentage does. Read only where it is drawn, in a
 * layer of its own, as the percentage is ([rememberCountUp]). Nothing for a screen reader: the
 * percentage beside it says it.
 */
@Composable
internal fun RevealBar(
    counted: State<Float>,
    fill: Color,
    track: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(WyrThemeAccessors.dimens.revealBarHeight)
                .graphicsLayer()
                .drawBehind {
                    drawRect(track)
                    val filled = size.width * (counted.value / FULL).coerceIn(0f, 1f)
                    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - filled else 0f
                    drawRect(fill, topLeft = Offset(x, 0f), size = Size(filled, size.height))
                },
    )
}

/** A whole share, the count's end when every answer is on one side. */
private const val FULL = 100f
