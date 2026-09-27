package io.ntole.wyr.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import io.ntole.wyr.analytics.tapped
import io.ntole.wyr.core.domain.analytics.AnalyticsProperty
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.navigation.HomeTopBar
import io.ntole.wyr.play.CountedUpText
import io.ntole.wyr.play.RevealBar
import io.ntole.wyr.play.cardMotion
import io.ntole.wyr.play.percentStyle
import io.ntole.wyr.play.rememberCountUp
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale

/** How long Home's shares take to count up once a Play button is tapped: shorter than the Play screen's. */
internal const val HOME_COUNT_UP_MILLIS = 1_200

/** How long the counted shares stay on Home before it fades into the game. */
internal const val HOME_HOLD_MILLIS = 1_000

/** Home's half of the fade into the Play screen, which fades in for [PLAY_ENTRANCE_MILLIS] after it. */
internal const val HOME_FADE_MILLIS = 200

/** Play's half of the fade from Home. */
internal const val PLAY_ENTRANCE_MILLIS = 250

/** From a tap on a Play button to the Play screen shown, the fade from Home included. */
internal const val HOME_REVEAL_MILLIS = HOME_COUNT_UP_MILLIS + HOME_HOLD_MILLIS + HOME_FADE_MILLIS

/** How much larger Home grows as it fades out, and how much smaller Play starts as it fades in. */
internal const val FADE_THROUGH_SCALE = 0.04f

/**
 * The Home screen, the one the app opens on (CLAUDE.md §8d, *Navigation*, *Home picks*): the game's
 * name and, under it, two small Play buttons side by side in the answer cards' colours (§5b), and the
 * account icon, top right, [onAccount], with a dot while [news] waits there. Nothing else, the user
 * asking for less text.
 *
 * A tap on a button is [onPick], told which, at once; then both buttons count up their share of every
 * player's taps, [picks] and this tap with them, as a reveal counts ([CountedUpText]), stay a moment, and
 * Home fades out into the game, [onPlay]. With no [picks] read there is nothing to count, and the game
 * starts at once. The timeline runs on the frame clock, so a test steps through it with the scene's.
 */
@Composable
fun HomeScreen(
    picks: Tally?,
    onPick: (Side) -> Unit,
    onPlay: () -> Unit,
    onAccount: () -> Unit,
    modifier: Modifier = Modifier,
    news: Boolean = false,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val strings = LocalStrings.current
    var picked by rememberSaveable { mutableStateOf<Side?>(null) }
    val haptics = LocalHapticFeedback.current
    // The tap counted on the device, so the reveal waits for nobody (the server hears of it in the background).
    val revealed = picked?.let { side -> picks?.plus(side) }
    val play by rememberUpdatedState(onPlay)
    // From the tap, in milliseconds: the count up, the hold, then Home's fade.
    val timeline = remember { Animatable(0f) }

    LaunchedEffect(picked) {
        if (picked == null) return@LaunchedEffect
        if (revealed != null) {
            timeline.animateTo(
                HOME_REVEAL_MILLIS.toFloat(),
                tween(durationMillis = HOME_REVEAL_MILLIS, easing = LinearEasing),
            )
        }
        play()
    }

    Surface(color = colors.pageBackground, modifier = modifier.fillMaxSize()) {
        Column(
            // Read only as it is drawn: the fade composes nothing.
            modifier =
                Modifier.graphicsLayer {
                    val fading = ((timeline.value - HOME_COUNT_UP_MILLIS - HOME_HOLD_MILLIS) / HOME_FADE_MILLIS)
                    val gone = fading.coerceIn(0f, 1f)
                    alpha = 1f - gone
                    scaleX = 1f + FADE_THROUGH_SCALE * gone
                    scaleY = scaleX
                },
        ) {
            HomeTopBar(onAccount = onAccount, news = news)

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(dimens.spaceXl, Alignment.CenterVertically),
                modifier = Modifier.fillMaxWidth().weight(1f).padding(dimens.screenPadding),
            ) {
                Text(
                    text = strings.gameName,
                    color = colors.headingAccent,
                    fontSize = WyrTypeScale.gameName,
                    lineHeight = WyrTypeScale.gameNameLineHeight,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(dimens.spaceMd),
                    modifier = Modifier.widthIn(max = dimens.homeButtonsMaxWidth).fillMaxWidth(),
                ) {
                    listOf(Side.A, Side.B).forEach { side ->
                        PlayButton(
                            side = side,
                            share = revealed?.percentOf(side),
                            rival = revealed?.percentOf(if (side == Side.A) Side.B else Side.A),
                            isPicked = picked == side,
                            isDimmed = picked != null && picked != side,
                            enabled = picked == null,
                            onClick = {
                                // Once: a screen reader's tap reaches a button that is off as well.
                                if (picked == null) {
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    picked = side
                                    onPick(side)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** [this] with one more tap on [side]'s button. */
private fun Tally.plus(side: Side): Tally = if (side == Side.A) copy(votesA = votesA + 1) else copy(votesB = votesB + 1)

/**
 * One of the two Play buttons, in [side]'s card colour: *Play* and, once a button is tapped, [share],
 * counted up from 0 with a bar along its bottom filling with it, as the reveal counts; sunk while
 * pressed, lifted when [isPicked] and faint when [isDimmed] ([cardMotion]). The share's room is kept from the start, so the reveal moves nothing. Its tap is
 * `home.play` to the analytics, with its side (CLAUDE.md §8g).
 */
@Composable
private fun PlayButton(
    side: Side,
    share: Int?,
    rival: Int?,
    isPicked: Boolean,
    isDimmed: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val strings = LocalStrings.current
    val shape = RoundedCornerShape(dimens.radiusCard)
    val background = if (side == Side.A) colors.optionA else colors.optionB
    val contentColor = if (side == Side.A) colors.onOptionA else colors.onOptionB

    Surface(
        onClick = tapped("home.play", mapOf(AnalyticsProperty.SIDE to side.name), onClick),
        enabled = enabled,
        shape = shape,
        color = background,
        contentColor = contentColor,
        interactionSource = interaction,
        modifier =
            modifier
                .heightIn(min = dimens.homeButtonHeight)
                .cardMotion(interaction, isPicked = isPicked, isDimmed = isDimmed, shape = shape, colour = background),
    ) {
        val counted = share?.let { rememberCountUp(it, rival = rival ?: it, durationMillis = HOME_COUNT_UP_MILLIS) }

        // As high as the Surface's least height, which it passes on, or its content: never the whole screen.
        Box(modifier = Modifier.fillMaxWidth()) {
            // Play over the widest share, unseen and unheard: the room the reveal takes, held from the start.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .padding(dimens.spaceMd)
                        .alpha(0f)
                        .clearAndSetSemantics {},
            ) {
                PlayLabel()
                Spacer(Modifier.size(dimens.spaceSm))
                Text(text = strings.playScreen.percent(FULL_SHARE), style = percentStyle())
            }
            // Play alone in the middle until a tap, then the share counting up under it.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center).padding(dimens.spaceMd),
            ) {
                PlayLabel()
                if (share != null && counted != null) {
                    Spacer(Modifier.size(dimens.spaceSm))
                    CountedUpText(
                        counted = counted,
                        target = share,
                        text = strings.playScreen::percent,
                        style = percentStyle(),
                    )
                }
            }
            if (counted != null) {
                RevealBar(
                    counted = counted,
                    fill = contentColor,
                    track = if (side == Side.A) colors.revealTrackOnA else colors.revealTrackOnB,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/** *Play*, on a Play button. */
@Composable
private fun PlayLabel() {
    Text(text = LocalStrings.current.play, fontSize = WyrTypeScale.playButton, fontWeight = FontWeight.Bold)
}

/** Every tap on one side: the widest a share is written. */
private const val FULL_SHARE = 100
