package io.ntole.wyr.play

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import io.ntole.wyr.analytics.tapped
import io.ntole.wyr.core.domain.analytics.AnalyticsProperty
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.reaction.Reaction
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.LocalLanguage
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.language.PlayStrings
import io.ntole.wyr.language.categoryName
import io.ntole.wyr.language.optionText
import io.ntole.wyr.loading.LoadingSpinner
import io.ntole.wyr.points.PointsAmount
import io.ntole.wyr.share.ShareButton
import io.ntole.wyr.share.ShareDialog
import io.ntole.wyr.share.ShareOutcome
import io.ntole.wyr.share.SharedQuestion
import io.ntole.wyr.theme.WyrIcons
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The game (CLAUDE.md §8d, *The Play screen*): two answer cards and, between them, one row of the
 * player's points, the like and the dislike, Share and Skip; on a wide screen the cards stand side by
 * side over the row (§8d, *Wide screens*). Tapping a card answers ([onChoose]); Skip ([onSkip]) goes past
 * a question not answered yet; once the answer is revealed, tapping either card goes on to the next
 * question ([onNext]). The thumbs ask for a reaction ([onReact]): the one tapped, or none when it is
 * the one the player holds. Share opens the dialog that shares the question on screen, with its results
 * once it is answered (§8d, *Sharing*), and [onShared] hears what was shared.
 *
 * [points] are the player's as the server last reported them, `null` until it has. The categories
 * played are on the top bar above it (`PlayTopBar`, [CategoriesPlayed]).
 */
@Composable
fun PlayScreen(
    state: PlayUiState,
    points: Int?,
    onChoose: (Side) -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit,
    onReact: (Reaction) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onShared: (question: SharedQuestion, withResults: Boolean, outcome: ShareOutcome) -> Unit = { _, _, _ -> },
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    // The question being shared, as it was when Share was tapped, while its dialog is open.
    var sharing by remember { mutableStateOf<SharedQuestion?>(null) }

    // The question last on screen, kept while the next one loads, so going on is one smooth change of the
    // cards' faces rather than a spinner between two questions; the spinner only past LOADING_GRACE.
    val held = remember { HeldQuestion() }
    when (state) {
        is PlayUiState.OnQuestion -> held.question = state
        is PlayUiState.Failed -> held.question = null
        PlayUiState.Loading -> Unit
    }
    val loading = state is PlayUiState.Loading
    var spinnerDue by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        spinnerDue = false
        if (loading) {
            val start = withFrameNanos { it }
            do {
                val now = withFrameNanos { it }
            } while (now - start < LOADING_GRACE.inWholeNanoseconds)
            spinnerDue = true
        }
    }
    val onScreen =
        when (state) {
            is PlayUiState.OnQuestion -> state
            PlayUiState.Loading -> held.question.takeUnless { spinnerDue }
            is PlayUiState.Failed -> null
        }

    Surface(color = colors.pageBackground, modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeContentPadding()
                    // The top's padding is the repeat notice's slot on a question, and a spacer otherwise.
                    .padding(start = dimens.screenPadding, end = dimens.screenPadding, bottom = dimens.screenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (onScreen != null) {
                RepeatNotice(shown = onScreen.question.answeredBefore)
                QuestionBody(
                    state = onScreen,
                    points = points,
                    // A question held while the next one loads takes no tap.
                    frozen = onScreen !== state,
                    onChoose = onChoose,
                    onSkip = onSkip,
                    onNext = onNext,
                    onReact = onReact,
                    onShare = { sharing = it },
                )
            } else if (state is PlayUiState.Failed) {
                Spacer(Modifier.height(dimens.screenPadding))
                FailureBody(state.error, onRetry = onRetry)
            } else {
                Spacer(Modifier.height(dimens.screenPadding))
                LoadingBody()
            }
        }
    }
    sharing?.let { question ->
        ShareDialog(
            question = question,
            onDismiss = { sharing = null },
            onShared = { withResults, outcome -> onShared(question, withResults, outcome) },
        )
    }
}

/** The question last on screen, written as it is composed; no state of its own, so it recomposes nothing. */
private class HeldQuestion {
    var question: PlayUiState.OnQuestion? = null
}

/**
 * How long a question stays on screen, taking no tap, while the next one loads before the spinner
 * shows instead: a question from the queue comes well within it, so going on shows no spinner.
 */
internal val LOADING_GRACE: Duration = 400.milliseconds

/** How long a card's old face takes to go, and its new one to come, when the next question does. */
internal const val FACE_OUT_MILLIS = 120
internal const val FACE_IN_MILLIS = 220

/** How far a card's face slides as it goes and comes, of the card's height. */
private const val FACE_SLIDE_DIVISOR = 8

/** What one card shows of one question: a new [questionId] is a new face, slid in. */
private data class CardFace(
    val questionId: String,
    val text: String,
    val percent: Int?,
    val rival: Int?,
)

/**
 * *Answered before*, small and muted, above the cards while the player has answered the question on
 * screen before (CLAUDE.md §8d, *The Play screen*). Its slot is there for every question, in the
 * screen's top padding, which it grows only past the padding's height, at a large font size: so a
 * question answered before moves nothing on the screen, and one not answered shows and says nothing.
 */
@Composable
private fun RepeatNotice(shown: Boolean) {
    val dimens = WyrThemeAccessors.dimens

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth().heightIn(min = dimens.screenPadding),
    ) {
        Text(
            text = LocalStrings.current.playScreen.answeredBefore,
            color = WyrThemeAccessors.colors.muted,
            fontSize = WyrTypeScale.statLabel,
            lineHeight = WyrTypeScale.statLabelLineHeight,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Laid out whatever it shows, so its slot is as high either way.
            modifier = if (shown) Modifier else Modifier.alpha(0f).clearAndSetSemantics {},
        )
    }
}

/**
 * The two cards and the row between them, or under them side by side on a wide screen
 * ([QuestionLayout]). Before the answer a card answers for its side, and Skip goes past it; once it
 * is revealed, either card is the way on, and Skip is gone. Off while anything is in flight, one
 * action at a time.
 */
@Composable
private fun ColumnScope.QuestionBody(
    state: PlayUiState.OnQuestion,
    points: Int?,
    frozen: Boolean,
    onChoose: (Side) -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit,
    onReact: (Reaction) -> Unit,
    onShare: (SharedQuestion) -> Unit,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val idle = !state.isBusy && !frozen
    val outcome = (state as? PlayUiState.Revealed)?.outcome
    // In Serbian Latin made Latin, as every Serbian Latin text is; as its author wrote it otherwise (§8f).
    val language = LocalLanguage.current
    // Before the answer a card's text says what a tap on it does; after, a screen reader is told.
    val clickLabel = if (outcome == null) null else LocalStrings.current.playScreen.nextQuestion
    // Whether the cards stand side by side, the row under both, as they were last laid out: each bar
    // stands along its card's edge by the row, so card B's is then along its bottom, as card A's is.
    // Known by the time a reveal draws a bar, a question having been asked first.
    var sideBySide by remember { mutableStateOf(false) }

    QuestionLayout(
        wideMinWidth = dimens.wideLayoutMinWidth,
        gap = dimens.spaceMd,
        modifier =
            Modifier.fillMaxWidth().weight(1f).onSizeChanged { size ->
                sideBySide = with(density) { standsSideBySide(size.width, size.height, dimens.wideLayoutMinWidth) }
            },
    ) {
        OptionCard(
            questionId = state.question.id,
            text = optionText(state.question.optionA, language),
            background = colors.optionA,
            contentColor = colors.onOptionA,
            barTrack = colors.revealTrackOnA,
            // The bars stand along the edges by the row, between the cards or under them.
            barAt = Alignment.BottomCenter,
            percent = outcome?.tally?.percentA,
            rival = outcome?.tally?.percentB,
            isYourPick = outcome?.yourSide == Side.A,
            isDimmed = outcome != null && outcome.yourSide != Side.A,
            enabled = idle,
            clickLabel = clickLabel,
            onClick =
                tapped("play.card_a", mapOf(AnalyticsProperty.ANSWERED to (outcome != null))) {
                    if (outcome == null) {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onChoose(Side.A)
                    } else {
                        onNext()
                    }
                },
        )

        MiddleRow(
            question = state.question,
            points = points,
            rowError = state.rowError,
            idle = idle,
            onReact = onReact,
            // Only before answering (CLAUDE.md §8d, *Skipping*): once revealed, a card is the way on.
            onSkip = if (state is PlayUiState.Asking) onSkip else null,
            onShare = {
                onShare(
                    SharedQuestion(
                        id = state.question.id,
                        categories = state.question.categories,
                        optionA = optionText(state.question.optionA, language),
                        optionB = optionText(state.question.optionB, language),
                        tally = outcome?.tally,
                        pick = outcome?.yourSide,
                    ),
                )
            },
        )

        OptionCard(
            questionId = state.question.id,
            text = optionText(state.question.optionB, language),
            background = colors.optionB,
            contentColor = colors.onOptionB,
            barTrack = colors.revealTrackOnB,
            barAt = if (sideBySide) Alignment.BottomCenter else Alignment.TopCenter,
            percent = outcome?.tally?.percentB,
            rival = outcome?.tally?.percentA,
            isYourPick = outcome?.yourSide == Side.B,
            isDimmed = outcome != null && outcome.yourSide != Side.B,
            enabled = idle,
            clickLabel = clickLabel,
            onClick =
                tapped("play.card_b", mapOf(AnalyticsProperty.ANSWERED to (outcome != null))) {
                    if (outcome == null) {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onChoose(Side.B)
                    } else {
                        onNext()
                    }
                },
        )
    }
}

/**
 * The one row between the cards ([PlayRow], CLAUDE.md §8d, *The Play screen*): on the left the
 * player's points, a coin and the number, or how the last reaction failed, and right beside them the
 * thumbs, the like and the dislike, each filled while the player holds it and beside how many hold it,
 * as the server counted them (CLAUDE.md §8d, *Reactions*), before answering and after; and on the right
 * Share ([onShare]), always, and Skip ([onSkip]) while the question is not answered yet, `null` once it
 * is. Skip's place is kept once it is gone, so the reveal moves nothing in the row. The thumbs, Share and
 * Skip are on only while the screen is [idle], and Share and Skip are drawn muted while they are off.
 *
 * A thumb asks for its reaction, or for none when the player holds it already ([onReact]). The row is
 * the thumbs' height whatever it shows, at any font size, so a failed reaction never grows it: it shows
 * in the points' place, no wider than `WyrDimens.playRowStartMaxWidth`, cut short on two lines there,
 * never the counts, Share or Skip, and the thumbs move over for it. Internal, not private, so a test can
 * measure it.
 */
@Composable
internal fun MiddleRow(
    question: Question,
    points: Int?,
    rowError: DomainError?,
    idle: Boolean,
    onReact: (Reaction) -> Unit,
    onSkip: (() -> Unit)?,
    onShare: () -> Unit,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val strings = LocalStrings.current.playScreen
    val touchTarget = LocalMinimumInteractiveComponentSize.current

    PlayRow(
        gap = dimens.spaceXs,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = dimens.spaceXs),
        start = {
            // Exactly as high as a thumb's touch target, which does not grow with the phone's font size
            // as text does. How a reaction failed shows in the points' place, until the next reaction
            // or the next question, in two short lines at most, set close enough to fit up to half again
            // the font size and cut short inside it past that, never growing the row.
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.widthIn(max = dimens.playRowStartMaxWidth).height(touchTarget),
            ) {
                if (rowError != null) {
                    Text(
                        text = failureText(rowError, strings),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = WyrTypeScale.statLabel,
                        lineHeight = WyrTypeScale.statLabelLineHeight,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (points != null) {
                    PointsAmount(points = points, fontWeight = FontWeight.Bold, color = colors.primaryText)
                }
            }
        },
        middle = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ReactionToggle(
                    held = question.myReaction == Reaction.LIKE,
                    count = question.likeCount,
                    icon = WyrIcons.ThumbUp,
                    heldIcon = WyrIcons.ThumbUpFilled,
                    name = strings.like,
                    enabled = idle,
                    onClick =
                        tapped(
                            "play.like",
                        ) { onReact(reactionAfterTap(Reaction.LIKE, held = question.myReaction)) },
                )
                ReactionToggle(
                    held = question.myReaction == Reaction.DISLIKE,
                    count = question.dislikeCount,
                    icon = WyrIcons.ThumbDown,
                    heldIcon = WyrIcons.ThumbDownFilled,
                    name = strings.dislike,
                    enabled = idle,
                    onClick =
                        tapped(
                            "play.dislike",
                        ) { onReact(reactionAfterTap(Reaction.DISLIKE, held = question.myReaction)) },
                )
            }
        },
        end = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShareButton(element = "play.share", enabled = idle, onClick = onShare)
                if (onSkip != null) {
                    val interaction = remember { MutableInteractionSource() }
                    IconButton(
                        onClick = tapped("play.skip", onClick = onSkip),
                        enabled = idle,
                        interactionSource = interaction,
                        modifier = Modifier.pressScale(interaction),
                    ) {
                        // Muted while off: a tint of its own hides the button's off colour.
                        Icon(
                            imageVector = WyrIcons.Skip,
                            contentDescription = strings.skip,
                            tint = if (idle) colors.headingAccent else colors.muted,
                        )
                    }
                } else {
                    Spacer(Modifier.size(touchTarget))
                }
            }
        },
    )
}

/**
 * What a tap on the thumb of [tapped] asks for, the player holding [held]: that reaction, or none when
 * it is the one they hold already, so a second tap takes it back.
 */
internal fun reactionAfterTap(
    tapped: Reaction,
    held: Reaction,
): Reaction = if (tapped == held) Reaction.NONE else tapped

/** One thumb, [heldIcon] while the player holds its reaction and [icon] while not, and its [count]. */
@Composable
private fun ReactionToggle(
    held: Boolean,
    count: Int,
    icon: ImageVector,
    heldIcon: ImageVector,
    name: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = WyrThemeAccessors.colors
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconToggleButton(
            checked = held,
            onCheckedChange = {
                haptics.performHapticFeedback(if (held) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                onClick()
            },
            enabled = enabled,
            interactionSource = interaction,
            modifier = Modifier.pressScale(interaction),
        ) {
            Icon(imageVector = if (held) heldIcon else icon, contentDescription = name, tint = colors.headingAccent)
        }
        Text(
            text = count.toString(),
            color = colors.primaryText,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.pulledIn(WyrThemeAccessors.dimens.reactionCountInset),
        )
    }
}

/**
 * Laid out [by] less wide and placed that far back, into the empty edge of the touch target before it,
 * which still takes the taps there: the thumb's count, beside its icon (`WyrDimens.reactionCountInset`).
 */
private fun Modifier.pulledIn(by: Dp): Modifier =
    layout { measurable, constraints ->
        val pull = by.roundToPx()
        val placeable = measurable.measure(constraints)
        layout((placeable.width - pull).coerceAtLeast(0), placeable.height) { placeable.placeRelative(-pull, 0) }
    }

/**
 * The categories played, *All* while none is picked, with a small chevron, in the middle of the Play
 * screen's top bar: a tap opens the Categories screen, the only way to choose them. It looks the same
 * while the categories cannot change, as it does for every question that loads: the tap then does
 * nothing. Cut short on its one line when the names outrun the bar.
 */
@Composable
internal fun CategoriesPlayed(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WyrThemeAccessors.colors
    val strings = LocalStrings.current.playScreen

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .clickable(
                    enabled = enabled,
                    onClickLabel = strings.changeCategories,
                    role = Role.Button,
                    onClick = tapped("top_bar.categories", onClick = onClick),
                ).minimumInteractiveComponentSize(),
    ) {
        Text(
            text = text,
            color = colors.headingAccent,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(imageVector = WyrIcons.ChevronDown, contentDescription = null, tint = colors.headingAccent)
    }
}

/**
 * The categories played, as the Play screen names them: [all] while none is selected, or their names
 * in [language] ([categoryName]), in the order the server lists them, as the Categories screen
 * does, and after them any the app has not read, by id.
 */
internal fun categoriesPlayed(
    categories: PlayedCategories,
    all: String,
    language: Language,
): String {
    val selected = categories.selected
    if (selected.isEmpty()) return all
    val listed = categories.known.map { it.id }.filter { it in selected }
    val unread = (selected - listed.toSet()).sorted()
    return (listed + unread).joinToString(", ") { categoryName(it, categories.known, language) }
}

/**
 * One answer card, in its side's brand colour (CLAUDE.md §5b), moving as [cardMotion] moves it: sunk
 * while pressed, lifted once it is the player's pick, faint while [isDimmed]. Once the answer is revealed
 * it shows its side's share, [percent], counted up from 0 ([CountedUpText]), and a bar along its edge at
 * [barAt], from one side of the card to the other, filling with the count to the share ([RevealBar]):
 * in [contentColor] on a [barTrack]. A new [questionId] slides the card's face on, the old one fading
 * up and away and the new one rising in, so the next question never snaps in. [clickLabel], if any, is
 * what a screen reader says a tap on it does.
 */
@Composable
private fun OptionCard(
    questionId: String,
    text: String,
    background: Color,
    contentColor: Color,
    barTrack: Color,
    barAt: Alignment,
    percent: Int?,
    rival: Int?,
    isYourPick: Boolean,
    isDimmed: Boolean,
    enabled: Boolean,
    clickLabel: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = WyrThemeAccessors.dimens
    val shape = RoundedCornerShape(dimens.radiusCard)
    val interaction = remember { MutableInteractionSource() }

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = background,
        contentColor = contentColor,
        interactionSource = interaction,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = dimens.optionMinHeight)
                // Only the label: without an action of its own, the tap stays the Surface's.
                .semantics { if (clickLabel != null) onClick(label = clickLabel, action = null) }
                .cardMotion(
                    interaction,
                    isPicked = isYourPick,
                    isDimmed = isDimmed,
                    shape = shape,
                    colour = background,
                ),
    ) {
        AnimatedContent(
            targetState = CardFace(questionId, text, percent, rival),
            contentKey = { it.questionId },
            transitionSpec = {
                val slideIn =
                    tween<IntOffset>(FACE_IN_MILLIS, delayMillis = FACE_OUT_MILLIS, easing = LinearOutSlowInEasing)
                (
                    fadeIn(tween(FACE_IN_MILLIS, delayMillis = FACE_OUT_MILLIS)) +
                        slideInVertically(slideIn) { it / FACE_SLIDE_DIVISOR }
                ) togetherWith (
                    fadeOut(tween(FACE_OUT_MILLIS)) +
                        slideOutVertically(tween(FACE_OUT_MILLIS, easing = FastOutLinearInEasing)) {
                            -it / FACE_SLIDE_DIVISOR
                        }
                ) using null
            },
            modifier = Modifier.fillMaxSize(),
            label = "cardFace",
        ) { face ->
            // The face going is not read out: only the one coming is the question on screen.
            val leaving = transition.targetState == EnterExitState.PostExit
            CardFaceContent(
                face = face,
                contentColor = contentColor,
                barTrack = barTrack,
                barAt = barAt,
                modifier = if (leaving) Modifier.clearAndSetSemantics {} else Modifier,
            )
        }
    }
}

/** One face of a card: its option and, once revealed, its share counted up and its bar. */
@Composable
private fun CardFaceContent(
    face: CardFace,
    contentColor: Color,
    barTrack: Color,
    barAt: Alignment,
    modifier: Modifier = Modifier,
) {
    val dimens = WyrThemeAccessors.dimens
    // One count for the percentage and the bar, so they move as one.
    val counted = face.percent?.let { rememberCountUp(it, rival = face.rival ?: it) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center).padding(dimens.spaceMd),
        ) {
            // As large as the card leaves it room for, the percentage's room taken first, and in steps no
            // smaller than the floor, so a long option shrinks rather than being cut (§8d, *The Play
            // screen*).
            Text(
                text = face.text,
                fontSize = WyrTypeScale.optionText,
                lineHeight = WyrTypeScale.optionLineHeight,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                autoSize = OptionTextAutoSize,
                modifier = Modifier.weight(1f, fill = false),
            )

            if (face.percent != null && counted != null) {
                Spacer(Modifier.size(dimens.spaceSm))
                CountedUpText(
                    counted = counted,
                    target = face.percent,
                    text = LocalStrings.current.playScreen::percent,
                    style = percentStyle(),
                )
            }
        }
        // Flush along the card's edge, from one side to the other, the card's shape clipping its ends round.
        if (counted != null) {
            RevealBar(
                counted = counted,
                fill = contentColor,
                track = barTrack,
                modifier = Modifier.align(barAt),
            )
        }
    }
}

/**
 * How an option fits its card: from [WyrTypeScale.optionText] down to [WyrTypeScale.optionTextMin],
 * [WyrTypeScale.optionTextStep] at a time, the largest that fits whole. One instance, as a text's
 * auto-size is compared by equality on every layout.
 */
private val OptionTextAutoSize: TextAutoSize =
    TextAutoSize.StepBased(
        minFontSize = WyrTypeScale.optionTextMin,
        maxFontSize = WyrTypeScale.optionText,
        stepSize = WyrTypeScale.optionTextStep,
    )

/**
 * The style of the reveal's percentages, as a `Text` of their size and weight takes it on the card: the
 * theme's text style, in the card's content colour. The Home screen's Play buttons show their shares in
 * it too, as the game does.
 */
@Composable
internal fun percentStyle(): TextStyle {
    val style = LocalTextStyle.current
    return style.merge(
        color = style.color.takeOrElse { LocalContentColor.current },
        fontSize = WyrTypeScale.percentage,
        fontWeight = FontWeight.ExtraBold,
    )
}

/**
 * A spinner, named for a screen reader: no text to read while a question loads, but for the one line a
 * slow load says under it (CLAUDE.md §8d, *A slow first load*).
 */
@Composable
private fun LoadingBody() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LoadingSpinner(name = LocalStrings.current.loading)
    }
}

/**
 * What failed, in one short sentence, and Try again. A selection with nothing to serve ends here, and
 * the categories played on the top bar above are the way out (CLAUDE.md §8d, *Categories*).
 */
@Composable
private fun FailureBody(
    error: DomainError,
    onRetry: () -> Unit,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val shared = LocalStrings.current
    val strings = shared.playScreen

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(dimens.spaceMd),
        ) {
            Text(
                text = failureText(error, strings),
                color = colors.primaryText,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium,
            )
            Button(onClick = tapped("play.try_again", onClick = onRetry)) { Text(shared.tryAgain) }
        }
    }
}

/**
 * What failed, in the player's words, by its [DomainError], never the server's message, which is
 * diagnostic and never translated: a question that failed to load or to be answered, and a
 * reaction that failed. The Play screen never submits, moderates or logs in, so every other error is the
 * same short sentence.
 */
internal fun failureText(
    error: DomainError,
    strings: PlayStrings,
): String =
    when (error) {
        DomainError.NETWORK -> strings.cannotReach
        DomainError.OUT_OF_QUESTIONS -> strings.outOfQuestions
        DomainError.RATE_LIMITED -> strings.slowDown
        DomainError.QUESTION_NOT_FOUND -> strings.questionGone
        else -> strings.somethingWrong
    }
