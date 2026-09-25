package io.ntole.wyr.submit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import io.ntole.wyr.core.domain.submission.OptionProblem
import io.ntole.wyr.core.domain.submission.SubmissionRules
import io.ntole.wyr.language.AccountStrings
import io.ntole.wyr.language.LocalLanguage
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.language.Strings
import io.ntole.wyr.language.categoryName
import io.ntole.wyr.language.fill
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale

/**
 * The Submit screen (CLAUDE.md §8d, *Submitting*), opened from My questions on the Account screen: a
 * question of the player's own, its two options and the categories it is filed under, sent for a
 * moderator to review. The categories are the server's, read each time the form is shown and named
 * in the language shown. Send names what it costs, [SubmissionRules.SUBMISSION_COST], and stays off
 * while the player has fewer points; once a question is stored the app goes back to My questions.
 *
 * Plain on purpose while UI polish is paused, every colour, space and size from the theme (§5b) and
 * every word from [LocalStrings] (§8f). Each option says what [SubmissionRules] refuses in it as it is
 * typed, and Send stays off until nothing is refused and a category is picked. The form scrolls.
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
        ) {
            Form(state, actions)
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
    val strings = LocalStrings.current.accountScreens
    val language = LocalLanguage.current
    // What is typed is not to change while it is being sent: it is cleared once it is stored.
    val editable = !state.isSubmitting

    Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceSm)) {
        Text(
            text = strings.wouldYouRather,
            color = colors.headingAccent,
            fontSize = WyrTypeScale.heading,
            fontWeight = FontWeight.ExtraBold,
        )
        OptionField(
            value = state.optionA,
            onValueChange = actions::setOptionA,
            label = strings.optionA,
            hint = optionHint(state.optionAProblem, same = false, strings),
            isError = state.optionAProblem != null,
            enabled = editable,
            imeAction = ImeAction.Next,
        )
        OptionField(
            value = state.optionB,
            onValueChange = actions::setOptionB,
            label = strings.optionB,
            hint = optionHint(state.optionBProblem, same = state.sameOptions, strings),
            isError = state.optionBProblem != null || state.sameOptions,
            enabled = editable,
            imeAction = ImeAction.Done,
        )

        SectionTitle(strings.categories)
        // No vertical spacing: each chip's touch target already stands clear of the row below.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm)) {
            state.categoryOptions.forEach { category ->
                FilterChip(
                    selected = category.id in state.categories,
                    onClick = { actions.toggleCategory(category.id) },
                    label = { Text(categoryName(category, language)) },
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
        Text(text = strings.pickCategories, color = colors.muted, fontSize = WyrTypeScale.statLabel)
        // When the points could not be read either, the one failure under Send says so, and its Try
        // again reads both.
        state.categoriesFailure?.takeIf { state.pointsFailure == null }?.let { failure ->
            Text(text = categoriesFailureText(failure, LocalStrings.current), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = actions::refresh, enabled = !state.isBusy) { Text(LocalStrings.current.tryAgain) }
        }

        // The server's refusal for points says what the line under it would: one line of it, not two.
        state.submitFailure
            ?.takeUnless { it.error == DomainError.NOT_ENOUGH_POINTS && state.tooFewPoints }
            ?.let { FailureText(it) }
        if (state.tooFewPoints) Text(text = strings.notEnoughPoints, color = colors.primaryText)
        Button(onClick = actions::submit, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth()) {
            Text(sendText(LocalStrings.current))
        }
        if (state.isBusy) {
            LinearProgressIndicator(color = colors.headingAccent, modifier = Modifier.fillMaxWidth())
        }
        state.pointsFailure?.let { failure ->
            FailureText(failure)
            OutlinedButton(onClick = actions::refresh, enabled = !state.isBusy) { Text(LocalStrings.current.tryAgain) }
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
    Text(
        text = failureMessage(failure, LocalStrings.current.accountScreens),
        color = MaterialTheme.colorScheme.error,
    )
}

/** Send, and what sending costs, in the points' one unit: *Пошаљи · 1 П*. */
internal fun sendText(strings: Strings): String =
    strings.accountScreens.send.fill(strings.points(SubmissionRules.SUBMISSION_COST))

/** The rule an option is held to, what is wrong with the one typed by it, or that the two are the same. */
internal fun optionHint(
    problem: OptionProblem?,
    same: Boolean,
    strings: AccountStrings,
): String =
    when {
        problem == OptionProblem.BLANK -> strings.optionBlank
        problem == OptionProblem.TOO_LONG -> strings.optionTooLong.fill(SubmissionRules.MAX_OPTION_LENGTH)
        problem == OptionProblem.NOT_ONE_LINE -> strings.optionNotOneLine
        same -> strings.optionsSame
        else -> strings.optionRule.fill(SubmissionRules.MAX_OPTION_LENGTH)
    }

/**
 * Why the categories could not be read, in one short line, the categories read before staying to pick
 * from: offline, in the words of the form's other failures, or anything else.
 */
internal fun categoriesFailureText(
    failure: SubmitFailure,
    strings: Strings,
): String =
    when (failure.error) {
        DomainError.NETWORK -> strings.accountScreens.offline
        else -> strings.categoriesUnread
    }

/**
 * Player-facing copy for a failed action, by its [DomainError], never the server's message, which is
 * diagnostic only.
 */
internal fun failureMessage(
    failure: SubmitFailure,
    strings: AccountStrings,
): String =
    when (failure.error) {
        DomainError.INVALID_SUBMISSION -> {
            strings.invalidSubmission
        }

        DomainError.SUBMISSION_LIMIT -> {
            strings.submissionLimit.fill(SubmissionRules.MAX_PENDING_SUBMISSIONS)
        }

        DomainError.NOT_ENOUGH_POINTS -> {
            strings.notEnoughPoints
        }

        DomainError.RATE_LIMITED -> {
            failure.retryAfter?.let { strings.tooManyTries.fill(it.inWholeSeconds) } ?: strings.tooManyTriesNoWait
        }

        DomainError.NETWORK -> {
            strings.offline
        }

        else -> {
            strings.somethingWrong
        }
    }
