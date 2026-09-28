package io.ntole.wyr.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import io.ntole.wyr.theme.WyrMotion
import io.ntole.wyr.theme.WyrThemeAccessors

/**
 * The screen on top of [navigator]'s back stack, [content] of it, and on each change a quick fade and a
 * slight slide from the old one to the new (CLAUDE.md §5b, *Motion*): a screen opened over the one
 * shown comes in from the end, the right in a left-to-right language, and going back, to a screen
 * lower on the stack, runs the other way. The first screen shown just appears, and so does Play opened
 * from Home, whose fade through the two screens run themselves (§8d, *Home picks*).
 *
 * Both move where they are drawn and placed, never composed again a frame, and once it ends the new
 * screen stands where it stood before there was motion. While it runs the old screen is still
 * composed, and goes once it ends.
 */
@Composable
internal fun ScreenTransitions(
    navigator: Navigator,
    modifier: Modifier = Modifier,
    content: @Composable (Screen) -> Unit,
) {
    val slide = with(LocalDensity.current) { WyrThemeAccessors.dimens.screenSlide.roundToPx() }
    val rightToLeft = LocalLayoutDirection.current == LayoutDirection.Rtl

    AnimatedContent(
        targetState = navigator.screens,
        modifier = modifier,
        // The same screen is the same content, however the stack under it changed.
        contentKey = { screens -> screens.last() },
        transitionSpec = {
            if (isFadeThrough(from = initialState.last(), to = targetState.last())) {
                ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
            } else {
                // Deeper is forward, over the one shown; shallower is back, to one under it.
                val forward = targetState.size >= initialState.size
                val towardEnd = if (forward != rightToLeft) 1 else -1
                screenTransition(slide * towardEnd)
            }
        },
        label = "screen",
    ) { screens -> content(screens.last()) }
}

/**
 * Whether the change [from] one screen [to] another is Home's Play buttons opening Play, which Home
 * fades out of and Play into by themselves, so it is left to them.
 */
internal fun isFadeThrough(
    from: Screen,
    to: Screen,
): Boolean = from == Screen.Home && to == Screen.Play

/**
 * The new screen fading in from [slide] pixels to its place, and the old one fading out as far the
 * other way, both over [WyrMotion.SCREEN_MILLIS]. No size animates: every screen fills its room.
 */
private fun screenTransition(slide: Int): ContentTransform {
    val offset = tween<IntOffset>(WyrMotion.SCREEN_MILLIS, easing = FastOutSlowInEasing)
    val fade = tween<Float>(WyrMotion.SCREEN_MILLIS, easing = FastOutSlowInEasing)
    return ContentTransform(
        targetContentEnter = fadeIn(fade) + slideInHorizontally(offset) { slide },
        initialContentExit = fadeOut(fade) + slideOutHorizontally(offset) { -slide },
        sizeTransform = null,
    )
}
