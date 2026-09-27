package io.ntole.wyr.play

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.DefaultShadowColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.ntole.wyr.theme.WyrThemeAccessors

/** How long a picked card takes to rise, and to settle back as the next question comes. */
internal const val PICK_LIFT_MILLIS = 250

/**
 * The pick, shown as height (CLAUDE.md §8d, *The Play screen*, *Home picks*): a card in [shape] rises to
 * `WyrDimens.pickElevation` while [isPicked], and eases back to none once it is not. Its shadow is black
 * on the light page and a glow of the card's own [colour] on the dark one, where black would not show.
 * The elevation is read only in the layer, so a frame of the lift composes nothing.
 */
@Composable
internal fun Modifier.pickLift(
    isPicked: Boolean,
    shape: Shape,
    colour: Color,
): Modifier {
    val glow = if (WyrThemeAccessors.colors.isDark) colour else DefaultShadowColor
    val lift =
        animateDpAsState(
            targetValue = if (isPicked) WyrThemeAccessors.dimens.pickElevation else 0.dp,
            animationSpec = tween(PICK_LIFT_MILLIS, easing = FastOutSlowInEasing),
            label = "pickLift",
        )
    return graphicsLayer {
        shadowElevation = lift.value.toPx()
        this.shape = shape
        ambientShadowColor = glow
        spotShadowColor = glow
    }
}
