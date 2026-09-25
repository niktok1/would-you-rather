package io.ntole.wyr.play

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.VoteOutcome
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale

/**
 * The game: a question, its two options, and the reveal once it is answered.
 *
 * [categories] are the categories played, none for every category, shown in the header, where
 * tapping them opens the category picker (CLAUDE.md §8d, *Categories*). [picking] is what the open
 * picker has ticked, or `null` while it is closed.
 */
@Composable
fun PlayScreen(
    state: PlayUiState,
    categories: Set<Category>,
    picking: Set<Category>?,
    onChoose: (Side) -> Unit,
    onSkip: () -> Unit,
    onToggleLike: () -> Unit,
    onNext: () -> Unit,
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
            Header(state, categories = categories, onOpenCategories = onOpenCategories)

            Spacer(Modifier.size(dimens.spaceMd))

            when (state) {
                PlayUiState.Loading -> {
                    LoadingBody()
                }

                is PlayUiState.Failed -> {
                    FailureBody(state.error, onRetry)
                }

                // Weighted, not filling: the row of controls below needs the height that is left.
                is PlayUiState.Asking -> {
                    QuestionBody(
                        question = state.question,
                        outcome = null,
                        likeError = state.likeError,
                        enabled = !state.isBusy,
                        onChoose = onChoose,
                        modifier = Modifier.weight(1f),
                    )
                }

                is PlayUiState.Revealed -> {
                    QuestionBody(
                        question = state.question,
                        outcome = state.outcome,
                        likeError = state.likeError,
                        enabled = false,
                        onChoose = onChoose,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (state is PlayUiState.OnQuestion) {
                Spacer(Modifier.size(dimens.spaceMd))
                Controls(state, onToggleLike = onToggleLike, onSkip = onSkip, onNext = onNext)
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
 * The title, and one row under it: the categories played, in every state, so a selection with
 * nothing to serve can be changed from the failure it leads to, and beside them, once a vote is
 * scored, the points.
 *
 * The categories are a [Stat] like the points, a value over its label, one line of each, so the row
 * is no taller for them than the reveal's points alone made it, and the reveal's option cards keep
 * the height their tally needs on a short phone (CLAUDE.md §8d, *Current focus*). Their value is in
 * the accent colour, since tapping it opens the picker. It looks the same while the categories
 * cannot change, as it does for every question that loads: the tap then does nothing.
 */
@Composable
private fun Header(
    state: PlayUiState,
    categories: Set<Category>,
    onOpenCategories: () -> Unit,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Would you rather...",
            color = colors.headingAccent,
            fontSize = WyrTypeScale.heading,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.size(dimens.spaceSm))
        Row(horizontalArrangement = Arrangement.spacedBy(dimens.spaceMd)) {
            // Weighted, so a long selection is cut short on its one line rather than pushing the
            // points off the row.
            Stat(
                label = "categories",
                value = categoriesPlayed(categories),
                valueColor = colors.headingAccent,
                modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .clickable(
                            enabled = state.canChangeCategories,
                            onClickLabel = "Change categories",
                            role = Role.Button,
                            onClick = onOpenCategories,
                        ),
            )

            // Points only mean something once the server has scored a vote, so they stay hidden
            // until there is a real number to show rather than a placeholder zero.
            val outcome = (state as? PlayUiState.Revealed)?.outcome
            if (outcome != null) {
                Stat(label = "points", value = outcome.totalPoints.toString())
                pointsThisVote(outcome)?.let { points -> Stat(label = "this vote", value = points) }
            }
        }
    }
}

/** The categories played, as the header names them: all of them while none is selected. */
internal fun categoriesPlayed(categories: Set<Category>): String =
    if (categories.isEmpty()) {
        "All"
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
 * What the vote earned, as the header shows it, or null for a replay (CLAUDE.md §8d, retry safety).
 * A replay pays 0 because the attempt it repeats was paid when it landed, so "+0" would tell the
 * player their answer earned nothing. The client never recomputes points (§8c), so it shows none.
 */
internal fun pointsThisVote(outcome: VoteOutcome): String? = if (outcome.replayed) null else "+${outcome.pointsAwarded}"

/** A value over its label, one line each, the value cut short rather than wrapped. */
@Composable
private fun Stat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = WyrThemeAccessors.colors.primaryText,
) {
    val colors = WyrThemeAccessors.colors

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(
            text = value,
            color = valueColor,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text = label, color = colors.muted, fontSize = WyrTypeScale.statLabel, maxLines = 1)
    }
}

@Composable
private fun QuestionBody(
    question: Question,
    outcome: VoteOutcome?,
    likeError: DomainError?,
    enabled: Boolean,
    onChoose: (Side) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens

    Column(modifier = modifier.fillMaxWidth()) {
        OptionCard(
            text = question.optionA,
            background = colors.optionA,
            contentColor = colors.onOptionA,
            percent = outcome?.tally?.percentA,
            votes = outcome?.tally?.votesA,
            isYourPick = outcome?.yourSide == Side.A,
            enabled = enabled,
            onClick = { onChoose(Side.A) },
            modifier = Modifier.weight(1f),
        )

        OrPill()

        OptionCard(
            text = question.optionB,
            background = colors.optionB,
            contentColor = colors.onOptionB,
            percent = outcome?.tally?.percentB,
            votes = outcome?.tally?.votesB,
            isYourPick = outcome?.yourSide == Side.B,
            enabled = enabled,
            onClick = { onChoose(Side.B) },
            modifier = Modifier.weight(1f),
        )

        // One line under the cards at most: how the last like failed, or else the reveal's verdict.
        // The failure takes the verdict's place, not a line of its own, so a short phone's reveal
        // keeps the height its cards need for the tally.
        val note = likeError?.let(::likeFailureMessage) ?: outcome?.let(::verdictLine)
        if (note != null) {
            Spacer(Modifier.size(dimens.spaceSm))
            Text(
                text = note,
                color = if (likeError != null) MaterialTheme.colorScheme.error else colors.muted,
                fontSize = WyrTypeScale.statLabel,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun verdictLine(outcome: VoteOutcome): String {
    val total = outcome.tally.total
    val people = if (total == 1L) "1 person has" else "$total people have"
    val verdict = if (outcome.agreedWithMajority) "You're with the crowd" else "You're the outlier"
    return "$verdict · $people answered this"
}

@Composable
private fun OptionCard(
    text: String,
    background: Color,
    contentColor: Color,
    percent: Int?,
    votes: Long?,
    isYourPick: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = WyrThemeAccessors.dimens
    val shape = RoundedCornerShape(dimens.radiusCard)

    // Animating from 0 makes the reveal read as the tally arriving, not as a layout jump.
    val revealed by animateFloatAsState(
        targetValue = if (percent == null) 0f else 1f,
        animationSpec = tween(durationMillis = 350),
    )

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
                .then(
                    if (isYourPick) {
                        Modifier.border(width = 4.dp, color = contentColor, shape = shape)
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
                        text = "$percent%",
                        fontSize = WyrTypeScale.percentage,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.alpha(revealed),
                    )
                    Text(
                        text = if (votes == 1L) "1 vote" else "${votes ?: 0} votes",
                        fontSize = WyrTypeScale.statLabel,
                        modifier = Modifier.alpha(revealed),
                    )
                }
            }
        }
    }
}

/**
 * One row under the question, asked or revealed: its like count, the player's Like (Unlike while
 * they like it), and the way on, Skip before answering and Next question after. The count is visible
 * before answering and is the server's (CLAUDE.md §8d, *Likes*), never worked out here.
 *
 * One row, where Next question alone stood before likes came, so the reveal is no taller than it
 * was and a short phone's option cards keep the height their tally needs. How a like failed shows
 * in the verdict's place (QuestionBody), not here.
 */
@Composable
private fun Controls(
    state: PlayUiState.OnQuestion,
    onToggleLike: () -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = likeCountOf(state.question),
            color = colors.primaryText,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onToggleLike, enabled = !state.isBusy) {
            Text(likeActionOf(state.question))
        }
        when (state) {
            is PlayUiState.Asking -> {
                OutlinedButton(onClick = onSkip, enabled = !state.isBusy) { Text("Skip") }
            }

            is PlayUiState.Revealed -> {
                Button(onClick = onNext, enabled = !state.isBusy) { Text("Next question") }
            }
        }
    }
}

/** How many players like [question], this one included, as the server counted them. */
internal fun likeCountOf(question: Question): String =
    if (question.likeCount == 1) "1 like" else "${question.likeCount} likes"

/** What the Like button does to [question]: unlike it when the player likes it, like it otherwise. */
internal fun likeActionOf(question: Question): String = if (question.likedByMe) "Unlike" else "Like"

/**
 * Player-facing copy for a like or unlike that failed, by its [DomainError], never the server's
 * message. The question stays as it was on screen, so pressing again is always the way to retry.
 */
internal fun likeFailureMessage(error: DomainError): String =
    when (error) {
        DomainError.NETWORK -> "Can't reach the game right now. Try again."
        DomainError.RATE_LIMITED -> "Slow down a moment, then try again."
        DomainError.QUESTION_NOT_FOUND -> "That question is no longer in the game."
        else -> "Something went wrong. Try again."
    }

@Composable
private fun OrPill() {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = dimens.spaceSm),
    ) {
        Surface(
            shape = RoundedCornerShape(dimens.radiusPill),
            color = colors.orPillBackground,
            contentColor = colors.orPillText,
        ) {
            Text(
                text = "OR",
                fontSize = WyrTypeScale.orPill,
                fontWeight = FontWeight.Black,
                modifier =
                    Modifier.padding(
                        horizontal = dimens.spaceMd,
                        vertical = dimens.spaceXs,
                    ),
            )
        }
    }
}

@Composable
private fun LoadingBody() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = WyrThemeAccessors.colors.headingAccent)
    }
}

@Composable
private fun FailureBody(
    error: DomainError,
    onRetry: () -> Unit,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message(error),
                color = colors.primaryText,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.size(dimens.spaceMd))
            Button(onClick = onRetry) { Text("Try again") }
        }
    }
}

/**
 * Player-facing copy per [DomainError].
 *
 * Branching on the domain error, never on a server message: `ErrorDto.message` is diagnostic and
 * unlocalised, so it must never reach a screen. The Play tab never submits or moderates a
 * question, nor registers or logs in; those errors have copy only because every [DomainError] does.
 */
private fun message(error: DomainError): String =
    when (error) {
        DomainError.NETWORK -> "Can't reach the game right now.\nCheck your connection."
        DomainError.OUT_OF_QUESTIONS -> "You've answered everything we have.\nCome back soon."
        DomainError.RATE_LIMITED -> "Slow down a moment, then try again."
        DomainError.UNAUTHORIZED -> "We couldn't verify your session.\nTrying again should fix it."
        DomainError.QUESTION_NOT_FOUND -> "That question disappeared.\nLet's find another."
        DomainError.ALREADY_VOTED -> "You've already answered that one."
        DomainError.INVALID_SUBMISSION -> "That question can't be sent as written."
        DomainError.SUBMISSION_LIMIT -> "You have too many questions waiting for review."
        DomainError.ALREADY_DECIDED -> "That question has already been reviewed."
        DomainError.WRONG_STATUS -> "That question can't be changed that way right now."
        DomainError.FORBIDDEN -> "That needs a moderator."
        DomainError.INVALID_USERNAME -> "That username can't be used."
        DomainError.INVALID_PASSWORD -> "That password can't be used."
        DomainError.USERNAME_TAKEN -> "That username is taken."
        DomainError.ALREADY_REGISTERED -> "You're registered already."
        DomainError.INVALID_LOGIN -> "Wrong username or password."
        DomainError.SERVER, DomainError.UNKNOWN -> "Something went wrong on our end."
    }
