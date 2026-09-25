package io.ntole.wyr.submit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.OptionProblem
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionRules
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.play.categoryName
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale

/**
 * The Submit screen (CLAUDE.md §8d, *Submitting*): a question of the player's own, its two options
 * and the categories it is filed under, sent for a moderator to review; below it, the player's own
 * submissions, newest first, and where each stands.
 *
 * Plain on purpose while UI polish is paused, and every colour, space and size from the theme (§5b).
 * Each option says what [SubmissionRules] refuses in it as it is typed, and Submit stays off until
 * nothing is refused and a category is picked. The form scrolls with the list under it.
 */
@Composable
fun SubmitScreen(
    state: SubmitState,
    actions: SubmitActions,
    modifier: Modifier = Modifier,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens

    Surface(color = colors.pageBackground, modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(dimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.spaceLg),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceSm)) {
                Text(
                    text = "Submit a question",
                    color = colors.headingAccent,
                    fontSize = WyrTypeScale.heading,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(text = POINTS_NOTE, color = colors.muted, fontSize = WyrTypeScale.statLabel)
            }

            Form(state, actions)
            MySubmissions(state, actions)
        }
    }
}

@Composable
private fun Form(
    state: SubmitState,
    actions: SubmitActions,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    // What is typed is not to change while it is being sent: it is cleared once it is stored.
    val editable = !state.isSubmitting

    Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceSm)) {
        SectionTitle("Would you rather…")
        OptionField(
            value = state.optionA,
            onValueChange = actions::setOptionA,
            label = "Option A",
            hint = optionHint(state.optionAProblem, same = false),
            isError = state.optionAProblem != null,
            enabled = editable,
            imeAction = ImeAction.Next,
        )
        OptionField(
            value = state.optionB,
            onValueChange = actions::setOptionB,
            label = "Option B",
            hint = optionHint(state.optionBProblem, same = state.sameOptions),
            isError = state.optionBProblem != null || state.sameOptions,
            enabled = editable,
            imeAction = ImeAction.Done,
        )

        SectionTitle("Categories")
        // No vertical spacing: each chip's touch target already stands clear of the row below.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm)) {
            Category.selectable.forEach { category ->
                FilterChip(
                    selected = category in state.categories,
                    onClick = { actions.toggleCategory(category) },
                    label = { Text(categoryName(category)) },
                    enabled = editable,
                    // The theme's primary, which is the palette's: it maps nothing to the container
                    // Material picks for a picked chip by default, which is Material's own lavender.
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                )
            }
        }
        Text(text = "Pick one or more.", color = colors.muted, fontSize = WyrTypeScale.statLabel)

        state.submitFailure?.let { FailureText(it) }
        if (state.sent) Text(text = SENT_NOTE, color = colors.primaryText)
        Button(onClick = actions::submit, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth()) {
            Text("Submit")
        }
        if (state.isSubmitting) {
            LinearProgressIndicator(color = colors.headingAccent, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun OptionField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hint: String,
    isError: Boolean,
    enabled: Boolean,
    imeAction: ImeAction,
) {
    // Not singleLine: an option up to 200 characters reads better wrapped, and a line break typed
    // anyway is what the one-line rule says is wrong.
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = { Text(hint) },
        isError = isError,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = imeAction),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MySubmissions(
    state: SubmitState,
    actions: SubmitActions,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens

    Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceSm)) {
        SectionTitle("My submissions")

        val submissions = state.submissions
        val failure = state.listFailure
        when {
            // With no failure a read is on its way: every action ends in one, and a read that fails
            // says so here, whatever the action before it ended in.
            submissions == null -> {
                if (failure == null) CircularProgressIndicator(color = colors.headingAccent)
            }

            submissions.isEmpty() -> {
                Text(text = "None yet. The questions you send show here.", color = colors.muted)
            }

            else -> {
                if (state.running == SubmitAction.LOAD) {
                    LinearProgressIndicator(color = colors.headingAccent, modifier = Modifier.fillMaxWidth())
                }
                submissions.forEach { SubmissionCard(it) }
            }
        }
        if (failure != null) {
            FailureText(failure)
            OutlinedButton(onClick = actions::refresh, enabled = !state.isBusy) { Text("Try again") }
        }
    }
}

@Composable
private fun SubmissionCard(submission: Submission) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens

    Surface(
        color = colors.surface,
        shape = RoundedCornerShape(dimens.radiusCard),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(dimens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(dimens.spaceXs),
        ) {
            Text(text = statusLine(submission), color = colors.headingAccent, fontWeight = FontWeight.Bold)
            Text(text = submission.optionA, color = colors.primaryText)
            Text(text = "or", color = colors.muted, fontSize = WyrTypeScale.statLabel)
            Text(text = submission.optionB, color = colors.primaryText)
            Text(
                text = categoryNames(submission.categories),
                color = colors.muted,
                fontSize = WyrTypeScale.statLabel,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = WyrThemeAccessors.colors.primaryText,
        fontSize = WyrTypeScale.sectionTitle,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun FailureText(failure: SubmitFailure) {
    Text(text = failureMessage(failure), color = MaterialTheme.colorScheme.error)
}

/**
 * What submitting a question costs, in points (CLAUDE.md §8c): the server's `Scoring.SUBMISSION_COST`,
 * which no client can see, copied for the screen's words alone. The server charges it, and says
 * `NOT_ENOUGH_POINTS` whatever this says, so a change to the one is a change to the other.
 */
internal const val SUBMISSION_COST: Int = 1

/**
 * Submitting costs a point, paid back on a rejection, and earns nothing by itself (CLAUDE.md §8c): an
 * author earns through likes.
 */
internal const val POINTS_NOTE: String =
    "Submitting a question costs $SUBMISSION_COST point, paid back if it is rejected. " +
        "Once approved, each like it gets earns you 1."

internal const val SENT_NOTE: String = "Sent. It waits below for a moderator to review it."

/** The rule an option is held to, what is wrong with the one typed by it, or that the two are the same. */
internal fun optionHint(
    problem: OptionProblem?,
    same: Boolean,
): String =
    when {
        problem == OptionProblem.BLANK -> "Write something here."
        problem == OptionProblem.TOO_LONG -> "At most ${SubmissionRules.MAX_OPTION_LENGTH} characters."
        problem == OptionProblem.NOT_ONE_LINE -> "One line, with no line breaks or tabs."
        same -> "The two options must be different."
        else -> "One line, up to ${SubmissionRules.MAX_OPTION_LENGTH} characters."
    }

/** Where [submission] stands with the moderator, a rejected one with the moderator's reason. */
internal fun statusLine(submission: Submission): String =
    when (submission.status) {
        SubmissionStatus.PENDING -> "Pending: waiting for a moderator"
        SubmissionStatus.APPROVED -> "Approved: in the game"
        SubmissionStatus.REJECTED -> submission.rejectionReason?.let { "Rejected: $it" } ?: "Rejected"
        SubmissionStatus.RETIRED -> "Retired: out of the game for now"
        SubmissionStatus.OTHER -> "Its status is one this version of the app can't show"
    }

/** The categories a question is filed under, in the player's words and in declaration order. */
internal fun categoryNames(categories: Set<Category>): String =
    Category.entries.filter { it in categories }.joinToString(", ", transform = ::categoryName)

/**
 * Player-facing copy for a failed action, by its [DomainError], never the server's message, which is
 * diagnostic only.
 */
internal fun failureMessage(failure: SubmitFailure): String =
    when (failure.error) {
        DomainError.INVALID_SUBMISSION -> {
            "The game can't take that question as written. Check both options."
        }

        DomainError.SUBMISSION_LIMIT -> {
            "You have ${SubmissionRules.MAX_PENDING_SUBMISSIONS} questions waiting for review already. " +
                "Send more once one is reviewed."
        }

        DomainError.NOT_ENOUGH_POINTS -> {
            "You need $SUBMISSION_COST point to submit. Answer a question to earn it."
        }

        DomainError.RATE_LIMITED -> {
            val wait = failure.retryAfter?.let { "Wait ${it.inWholeSeconds} s" } ?: "Wait a moment"
            "Too many tries. $wait, then try again."
        }

        DomainError.NETWORK -> {
            "Can't reach the game. Check your connection."
        }

        else -> {
            "Something went wrong. Try again."
        }
    }
