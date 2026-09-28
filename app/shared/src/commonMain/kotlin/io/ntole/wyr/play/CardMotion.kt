package io.ntole.wyr.play

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.DefaultShadowColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.ntole.wyr.theme.WyrThemeAccessors

/** How long a card takes to sink under the finger, quick so the press is felt at once. */
internal const val PRESS_MILLIS = 100

/** How long the picked card takes to rise, and to settle back flat as the next question comes. */
internal const val PICK_LIFT_MILLIS = 250

/** How far a card sinks while pressed, of its size. */
private const val CARD_PRESSED_SCALE = 0.97f

/** How far a small button, a thumb or Skip, sinks while pressed. */
private const val BUTTON_PRESSED_SCALE = 0.85f

/** How faint the card not picked stands during the reveal, so the pick stands out. */
private const val UNPICKED_ALPHA = 0.85f

/**
 * An answer card's motion (CLAUDE.md §8d, *The Play screen*, *Home picks*): while [interaction] is
 * pressed it sinks, a little smaller and flat; let go, it springs back with a small overshoot; while
 * [isPicked] it lifts to `WyrDimens.pickElevation`, a black shadow on the light page and a glow of its
 * own [colour] on the dark one (`pickGlowElevation`, higher since a glow shows less), easing back flat
 * once it is not; and while [isDimmed], the card not picked in a reveal, it stands a little faint.
 * Every value is read only in the layer, so a frame of the motion composes nothing.
 */
@Composable
internal fun Modifier.cardMotion(
    interaction: InteractionSource,
    isPicked: Boolean,
    isDimmed: Boolean,
    shape: Shape,
    colour: Color,
): Modifier {
    val dimens = WyrThemeAccessors.dimens
    val dark = WyrThemeAccessors.colors.isDark
    val pressed by interaction.collectIsPressedAsState()
    val lifted = if (dark) dimens.pickGlowElevation else dimens.pickElevation
    val glow = if (dark) colour else DefaultShadowColor

    val scale =
        animateFloatAsState(
            targetValue = if (pressed) CARD_PRESSED_SCALE else 1f,
            animationSpec =
                if (pressed) {
                    tween(PRESS_MILLIS)
                } else {
                    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
                },
            label = "cardScale",
        )
    val elevation =
        animateDpAsState(
            targetValue = if (isPicked && !pressed) lifted else 0.dp,
            animationSpec = tween(if (pressed) PRESS_MILLIS else PICK_LIFT_MILLIS, easing = FastOutSlowInEasing),
            label = "cardLift",
        )
    val alpha =
        animateFloatAsState(
            targetValue = if (isDimmed) UNPICKED_ALPHA else 1f,
            animationSpec = tween(PICK_LIFT_MILLIS),
            label = "cardDim",
        )
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
        this.alpha = alpha.value
        shadowElevation = elevation.value.toPx()
        this.shape = shape
        ambientShadowColor = glow
        spotShadowColor = glow
    }
}

/**
 * A small button's press (a thumb, Skip): it sinks under the finger and springs back once let go,
 * read only in the layer.
 */
@Composable
internal fun Modifier.pressScale(interaction: InteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale =
        animateFloatAsState(
            targetValue = if (pressed) BUTTON_PRESSED_SCALE else 1f,
            animationSpec =
                if (pressed) {
                    tween(PRESS_MILLIS)
                } else {
                    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
                },
            label = "buttonScale",
        )
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
