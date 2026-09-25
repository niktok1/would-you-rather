package io.ntole.wyr.play

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.language.PlayStrings
import io.ntole.wyr.theme.WyrIcons
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale
import kotlin.math.roundToInt

/**
 * The game (CLAUDE.md §8d, *The Play screen*): two answer cards and, between them, one row of the
 * categories played, the player's points, the like and Skip. Tapping a card answers ([onChoose]);
 * Skip ([onSkip]) goes past a question not answered yet; once the answer is revealed, tapping either
 * card goes on to the next question ([onNext]).
 *
 * [categories] are the categories played, none for every category, and tapping them opens the
 * category picker ([onOpenCategories]), a dialog over the screen while [picking], what it has
 * ticked, is not `null`. [points] are the player's as the server last reported them, `null` until
 * it has.
 */
@Composable
fun PlayScreen(
    state: PlayUiState,
    categories: Set<Category>,
    points: Int?,
    picking: Set<Category>?,
    onChoose: (Side) -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit,
    onToggleLike: () -> Unit,
    onRetry: () -> Unit,
    onOpenCategories: () -> Unit,
    onToggleCategory: (Category) -> Unit,
    onSelectAllCategories: () -> Unit,
    onApplyCategories: () -> Unit,
    onCloseCategories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens

    Surface(color = colors.pageBackground, modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeContentPadding()
                    .padding(dimens.screenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state) {
                PlayUiState.Loading -> {
                    LoadingBody()
                }

                is PlayUiState.Failed -> {
                    FailureBody(state.error, categories, onRetry = onRetry, onOpenCategories = onOpenCategories)
                }

                is PlayUiState.OnQuestion -> {
                    QuestionBody(
                        state = state,
                        categories = categories,
                        points = points,
                        onChoose = onChoose,
                        onSkip = onSkip,
                        onNext = onNext,
                        onToggleLike = onToggleLike,
                        onOpenCategories = onOpenCategories,
                    )
                }
            }
        }

        if (picking != null) {
            Dialog(onDismissRequest = onCloseCategories) {
                CategoryPicker(
                    ticked = picking,
                    canApply = state.canChangeCategories,
                    onToggle = onToggleCategory,
                    onSelectAll = onSelectAllCategories,
                    onApply = onApplyCategories,
                    onClose = onCloseCategories,
                )
            }
        }
    }
}

/**
 * The two cards and the row between them. Before the answer a card answers for its side, and Skip
 * goes past it; once it is revealed, either card is the way on, and Skip is gone. Off while anything
 * is in flight, one action at a time.
 */
@Composable
private fun QuestionBody(
    state: PlayUiState.OnQuestion,
    categories: Set<Category>,
    points: Int?,
    onChoose: (Side) -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit,
    onToggleLike: () -> Unit,
    onOpenCategories: () -> Unit,
) {
    val colors = WyrThemeAccessors.colors
    val outcome = (state as? PlayUiState.Revealed)?.outcome
    // Before the answer a card's text says what a tap on it does; after, a screen reader is told.
    val clickLabel = if (outcome == null) null else LocalStrings.current.playScreen.nextQuestion

    Column(modifier = Modifier.fillMaxSize()) {
        OptionCard(
            text = state.question.optionA,
            background = colors.optionA,
            contentColor = colors.onOptionA,
            percent = outcome?.tally?.percentA,
            isYourPick = outcome?.yourSide == Side.A,
            enabled = !state.isBusy,
            clickLabel = clickLabel,
            onClick = { if (outcome == null) onChoose(Side.A) else onNext() },
            modifier = Modifier.weight(1f),
        )

        MiddleRow(
            question = state.question,
            categoriesPlayed = categoriesPlayed(categories, all = LocalStrings.current.playScreen.allCategories),
            points = points,
            likeError = state.likeError,
            canChangeCategories = state.canChangeCategories,
            idle = !state.isBusy,
            onOpenCategories = onOpenCategories,
            onToggleLike = onToggleLike,
            // Only before answering (CLAUDE.md §8d, *Skipping*): once revealed, a card is the way on.
            onSkip = if (state is PlayUiState.Asking) onSkip else null,
        )

        OptionCard(
            text = state.question.optionB,
            background = colors.optionB,
            contentColor = colors.onOptionB,
            percent = outcome?.tally?.percentB,
            isYourPick = outcome?.yourSide == Side.B,
            enabled = !state.isBusy,
            clickLabel = clickLabel,
            onClick = { if (outcome == null) onChoose(Side.B) else onNext() },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The one row between the cards (`CentredRow`): on the left the categories played, which open the
 * picker; in the middle the player's points, or how the last like failed; on the right the heart,
 * the player's own like, filled while they like the question, how many like it, as the server
 * counted them (CLAUDE.md §8d, *Likes*), before answering and after, and Skip ([onSkip]) while the
 * question is not answered yet, `null` once it is. Skip's place is kept once it is gone, so the
 * reveal moves nothing in the row. The heart and Skip are on only while the screen is [idle].
 *
 * It is the heart's height whatever it shows, at any font size, so a failed like moves nothing. The
 * points stand in the middle of the screen unless the categories played need their room, and a long
 * selection is cut short on its one line, never the like count or Skip: the middle is no wider than
 * `WyrDimens.playRowMiddleMaxWidth`. Internal, not private, so a test can measure it.
 */
@Composable
internal fun MiddleRow(
    question: Question,
    categoriesPlayed: String,
    points: Int?,
    likeError: DomainError?,
    canChangeCategories: Boolean,
    idle: Boolean,
    onOpenCategories: () -> Unit,
    onToggleLike: () -> Unit,
    onSkip: (() -> Unit)?,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val strings = LocalStrings.current.playScreen
    val touchTarget = LocalMinimumInteractiveComponentSize.current

    CentredRow(
        gap = dimens.spaceSm,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = dimens.spaceXs),
        start = { CategoriesPlayed(categoriesPlayed, enabled = canChangeCategories, onClick = onOpenCategories) },
        middle = {
            // No wider than its cap, so the like count and Skip always have their width, and exactly
            // as high as the heart's touch target, which does not grow with the phone's font size as
            // text does. How a like failed shows in the points' place, until the next like or the next
            // question, in two short lines at most, set close enough to fit up to half again the font
            // size and cut short inside it past that, never growing the row.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.widthIn(max = dimens.playRowMiddleMaxWidth).height(touchTarget),
            ) {
                if (likeError != null) {
                    Text(
                        text = failureText(likeError, strings),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = WyrTypeScale.statLabel,
                        lineHeight = WyrTypeScale.statLabelLineHeight,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (points != null) {
                    Text(
                        text = strings.points(points),
                        color = colors.primaryText,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        end = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconToggleButton(checked = question.likedByMe, onCheckedChange = { onToggleLike() }, enabled = idle) {
                    Icon(
                        imageVector = if (question.likedByMe) WyrIcons.HeartFilled else WyrIcons.Heart,
                        contentDescription = strings.like,
                        tint = colors.headingAccent,
                    )
                }
                Text(
                    text = question.likeCount.toString(),
                    color = colors.primaryText,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                if (onSkip != null) {
                    IconButton(onClick = onSkip, enabled = idle) {
                        Icon(
                            imageVector = WyrIcons.Skip,
                            contentDescription = strings.skip,
                            tint = colors.headingAccent,
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
 * The categories played, *All* while none is picked, with a small chevron: a tap opens the category
 * picker, the only way to choose them. It looks the same while the categories cannot change, as it
 * does for every question that loads: the tap then does nothing.
 */
@Composable
private fun CategoriesPlayed(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = WyrThemeAccessors.colors
    val strings = LocalStrings.current.playScreen

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .clickable(
                    enabled = enabled,
                    onClickLabel = strings.changeCategories,
                    role = Role.Button,
                    onClick = onClick,
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

/** The categories played, as the Play screen names them: [all] while none is selected. */
internal fun categoriesPlayed(
    categories: Set<Category>,
    all: String,
): String =
    if (categories.isEmpty()) {
        all
    } else {
        // In declaration order, as the picker lists them, whatever order the set holds them in.
        Category.entries.filter { it in categories }.joinToString(", ", transform = ::categoryName)
    }

/** A category in the player's words, never its wire name. */
internal fun categoryName(category: Category): String =
    when (category) {
        Category.FOOD -> "Food"
        Category.LIFESTYLE -> "Lifestyle"
        Category.ETHICS -> "Ethics"
        Category.SUPERPOWERS -> "Superpowers"
        Category.RANDOM -> "Random"
        Category.OTHER -> "Other"
    }

/**
 * The category picker's card, in a dialog over the screen: every category the feed can be filtered
 * to, ticked or not, and All categories, ticked while none is. Nothing is played until Play, and
 * Play is off while the screen cannot take a change ([canApply]), one action at a time.
 *
 * The list scrolls, so a window shorter than the card keeps Play on screen. Internal, not private,
 * so a test can measure it.
 */
@Composable
internal fun CategoryPicker(
    ticked: Set<Category>,
    canApply: Boolean,
    onToggle: (Category) -> Unit,
    onSelectAll: () -> Unit,
    onApply: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens

    Surface(
        shape = RoundedCornerShape(dimens.radiusCard),
        color = colors.surface,
        contentColor = colors.primaryText,
    ) {
        Column(modifier = Modifier.padding(dimens.spaceLg)) {
            Text(
                text = "Play these categories",
                color = colors.headingAccent,
                fontSize = WyrTypeScale.sectionTitle,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.size(dimens.spaceSm))

            Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                CategoryOption(label = "All categories", ticked = ticked.isEmpty(), onClick = onSelectAll)
                Category.selectable.forEach { category ->
                    CategoryOption(
                        label = categoryName(category),
                        ticked = category in ticked,
                        onClick = { onToggle(category) },
                    )
                }
            }

            Spacer(Modifier.size(dimens.spaceMd))
            Row(
                horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm, Alignment.End),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onClose) { Text("Cancel") }
                Button(onClick = onApply, enabled = canApply) { Text("Play") }
            }
        }
    }
}

/**
 * One line of the picker, ticked or not; the whole line toggles it, and is at least as tall as a
 * touch target, which a checkbox without a click of its own is not.
 */
@Composable
private fun CategoryOption(
    label: String,
    ticked: Boolean,
    onClick: () -> Unit,
) {
    val dimens = WyrThemeAccessors.dimens

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm),
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = ticked, role = Role.Checkbox, onValueChange = { onClick() })
                .minimumInteractiveComponentSize(),
    ) {
        // No click of its own: the line's toggleable is the one.
        Checkbox(checked = ticked, onCheckedChange = null)
        Text(text = label)
    }
}

/**
 * One answer card, in its side's brand colour (CLAUDE.md §5b), outlined once it is the player's pick.
 * Once the answer is revealed it shows its side's share, [percent], counted up from 0 ([countedUp]).
 * [clickLabel], if any, is what a screen reader says a tap on it does.
 */
@Composable
private fun OptionCard(
    text: String,
    background: Color,
    contentColor: Color,
    percent: Int?,
    isYourPick: Boolean,
    enabled: Boolean,
    clickLabel: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = WyrThemeAccessors.dimens
    val shape = RoundedCornerShape(dimens.radiusCard)

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = background,
        contentColor = contentColor,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = dimens.optionMinHeight)
                // Only the label: without an action of its own, the tap stays the Surface's.
                .semantics { if (clickLabel != null) onClick(label = clickLabel, action = null) }
                .then(
                    if (isYourPick) {
                        Modifier.border(width = dimens.pickBorder, color = contentColor, shape = shape)
                    } else {
                        Modifier
                    },
                ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(dimens.spaceMd),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = text,
                    fontSize = WyrTypeScale.optionText,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )

                if (percent != null) {
                    Spacer(Modifier.size(dimens.spaceSm))
                    Text(
                        text = LocalStrings.current.playScreen.percent(countedUp(percent)),
                        fontSize = WyrTypeScale.percentage,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
        }
    }
}

/**
 * How long the reveal's percentages take to count up from 0, both cards at once (CLAUDE.md §8d,
 * *The Play screen*). Internal, so a test can step the clock to it.
 */
internal const val COUNT_UP_MILLIS = 2_500

/**
 * [target] as the reveal shows it: counted up from 0 over [COUNT_UP_MILLIS], fast at first and
 * slowing into the value, once per reveal. The text alone counts; nothing moves.
 */
@Composable
private fun countedUp(target: Int): Int {
    val counted = remember { Animatable(0f) }
    LaunchedEffect(target) {
        counted.animateTo(
            targetValue = target.toFloat(),
            animationSpec = tween(durationMillis = COUNT_UP_MILLIS, easing = LinearOutSlowInEasing),
        )
    }
    return counted.value.roundToInt()
}

/** A spinner, named for a screen reader: no text to read while a question loads. */
@Composable
private fun LoadingBody() {
    val loading = LocalStrings.current.playScreen.loading

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            color = WyrThemeAccessors.colors.headingAccent,
            modifier = Modifier.semantics { contentDescription = loading },
        )
    }
}

/**
 * What failed, in one short sentence, Try again, and the categories played: a selection with
 * nothing to serve ends here, and changing it is the way out (CLAUDE.md §8d, *Categories*).
 */
@Composable
private fun FailureBody(
    error: DomainError,
    categories: Set<Category>,
    onRetry: () -> Unit,
    onOpenCategories: () -> Unit,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val strings = LocalStrings.current.playScreen

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
            Button(onClick = onRetry) { Text(strings.tryAgain) }
            CategoriesPlayed(
                text = categoriesPlayed(categories, all = strings.allCategories),
                enabled = true,
                onClick = onOpenCategories,
            )
        }
    }
}

/**
 * What failed, in the player's words, by its [DomainError], never the server's message, which is
 * diagnostic and never translated: a question that failed to load or to be answered, and a like
 * that failed. The Play screen never submits, moderates or logs in, so every other error is the
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
