package io.ntole.wyr.play

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.VoteOutcome
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale

@Composable
fun PlayScreen(
    state: PlayUiState,
    onChoose: (Side) -> Unit,
    onNext: () -> Unit,
    onRetry: () -> Unit,
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
            Header(state)

            Spacer(Modifier.size(dimens.spaceMd))

            when (state) {
                PlayUiState.Loading -> {
                    LoadingBody()
                }

                is PlayUiState.Failed -> {
                    FailureBody(state.error, onRetry)
                }

                is PlayUiState.Asking -> {
                    QuestionBody(
                        question = state.question,
                        outcome = null,
                        enabled = !state.isSubmitting,
                        onChoose = onChoose,
                    )
                }

                is PlayUiState.Revealed -> {
                    QuestionBody(
                        question = state.question,
                        outcome = state.outcome,
                        enabled = false,
                        onChoose = onChoose,
                    )
                }
            }

            if (state is PlayUiState.Revealed) {
                Spacer(Modifier.size(dimens.spaceMd))
                Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                    Text("Next question")
                }
            }
        }
    }
}

@Composable
private fun Header(state: PlayUiState) {
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

        // Points only mean something once the server has scored a vote, so they stay hidden
        // until there is a real number to show rather than a placeholder zero.
        val outcome = (state as? PlayUiState.Revealed)?.outcome
        if (outcome != null) {
            Spacer(Modifier.size(dimens.spaceSm))
            Row(horizontalArrangement = Arrangement.spacedBy(dimens.spaceMd)) {
                Stat(label = "points", value = outcome.totalPoints.toString())
                Stat(label = "this vote", value = "+${outcome.pointsAwarded}")
            }
        }
    }
}

@Composable
private fun Stat(
    label: String,
    value: String,
) {
    val colors = WyrThemeAccessors.colors

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, color = colors.primaryText, fontWeight = FontWeight.Bold)
        Text(text = label, color = colors.muted, fontSize = WyrTypeScale.statLabel)
    }
}

@Composable
private fun QuestionBody(
    question: Question,
    outcome: VoteOutcome?,
    enabled: Boolean,
    onChoose: (Side) -> Unit,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens

    Column(modifier = Modifier.fillMaxSize()) {
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

        if (outcome != null) {
            Spacer(Modifier.size(dimens.spaceSm))
            Text(
                text = verdictLine(outcome),
                color = colors.muted,
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
 * unlocalised, so it must never reach a screen.
 */
private fun message(error: DomainError): String =
    when (error) {
        DomainError.NETWORK -> "Can't reach the game right now.\nCheck your connection."
        DomainError.OUT_OF_QUESTIONS -> "You've answered everything we have.\nCome back soon."
        DomainError.RATE_LIMITED -> "Slow down a moment, then try again."
        DomainError.UNAUTHORIZED -> "We couldn't verify your session.\nTrying again should fix it."
        DomainError.QUESTION_NOT_FOUND -> "That question disappeared.\nLet's find another."
        DomainError.ALREADY_VOTED -> "You've already answered that one."
        DomainError.SERVER, DomainError.UNKNOWN -> "Something went wrong on our end."
    }
