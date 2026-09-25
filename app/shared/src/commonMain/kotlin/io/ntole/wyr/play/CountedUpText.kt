package io.ntole.wyr.play

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.roundToInt

/**
 * How long the reveal's percentages take to count up from 0, both cards at once (CLAUDE.md §8d,
 * *The Play screen*). Internal, so a test can step the clock to it.
 */
internal const val COUNT_UP_MILLIS = 2_500

/**
 * [target] as the reveal shows it, worded by [text], in [style]: counted up from 0 over
 * [COUNT_UP_MILLIS], fast at first and slowing into the value, once per reveal. The text alone
 * counts; nothing moves.
 *
 * The count is drawn, never composed (CLAUDE.md §8d, *The Play screen*). The number it has reached is
 * read only where it is drawn, so a frame of the count draws that number again and does nothing else:
 * nothing is composed or laid out again, and no semantics change reaches an accessibility service. A
 * debug build, whose Compose runs several times slower than a release build's, has no time for more
 * at 60 frames a second. [target]'s own text is laid out once: it sizes the box, so nothing beside it
 * moves as the count widens, and it is what a screen reader reads, the share itself rather than a
 * number on the way to it. The number reached is drawn in its place, in the middle of it.
 */
@Composable
internal fun CountedUpText(
    target: Int,
    text: (Int) -> String,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val counted = remember { Animatable(0f) }
    LaunchedEffect(target) {
        counted.animateTo(
            targetValue = target.toFloat(),
            animationSpec = tween(durationMillis = COUNT_UP_MILLIS, easing = LinearOutSlowInEasing),
        )
    }
    // The whole number reached: a frame that moves the count within one number draws nothing again.
    val reached = remember { derivedStateOf { counted.value.roundToInt() } }
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
