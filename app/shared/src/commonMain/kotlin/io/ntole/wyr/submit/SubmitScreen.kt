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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import io.ntole.wyr.about.SiteLink
import io.ntole.wyr.about.SiteLinksLine
import io.ntole.wyr.about.SitePage
import io.ntole.wyr.analytics.tapped
import io.ntole.wyr.core.domain.analytics.AnalyticsProperty
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.submission.OptionProblem
import io.ntole.wyr.core.domain.submission.SubmissionRules
import io.ntole.wyr.language.AccountStrings
import io.ntole.wyr.language.LocalLanguage
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.language.Strings
import io.ntole.wyr.language.categoryName
import io.ntole.wyr.language.fill
import io.ntole.wyr.points.PointsText
import io.ntole.wyr.theme.PageSurface
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale
import io.ntole.wyr.theme.contentWidth

/**
 * The Submit screen (CLAUDE.md §8d, *Submitting*), opened from My questions on the Account screen: a
 * question of the player's own, its two options and the categories it is filed under, sent for a
 * moderator to review. The categories are the server's, read each time the form is shown and named
 * in the language shown. Send names what it costs, [SubmitState.submissionCost], the server's cost read
 * with the points, a coin and the number, and stays off while the player has fewer points, or is a guest, who is told to register
 * first (CLAUDE.md §8d, *Submitting*); once a question is stored the app goes back to My questions.
 *
 * Plain on purpose while UI polish is paused, every colour, space and size from the theme (§5b) and
 * every word from [LocalStrings] (§8f). Each option says what [SubmissionRules] refuses in it as it is
 * typed, and Send stays off until nothing is refused and a category is picked, or *Ништа не одговара*,
 * with a category suggested or none, for the moderator to file it (CLAUDE.md §8d, *Categories*,
 * *Nothing fits*). The form scrolls.
 */
@Composable
fun SubmitScreen(
    state: SubmitState,
    actions: SubmitActions,
    modifier: Modifier = Modifier,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens

    PageSurface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(dimens.screenPadding)
                    .contentWidth(dimens.contentMaxWidth),
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
                    onClick =
                        tapped("submit.category", mapOf(AnalyticsProperty.CATEGORY to category.id)) {
                            actions.toggleCategory(category.id)
                        },
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
            // Only once there are categories to say none of fits (CLAUDE.md §8d, *Categories*, *Nothing
            // fits*): the moderator files the question.
            if (state.categoryOptions.isNotEmpty()) {
                FilterChip(
                    selected = state.nothingFits,
                    onClick = tapped("submit.nothing_fits", onClick = actions::toggleNothingFits),
                    label = { Text(strings.nothingFits) },
                    enabled = editable,
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                )
            }
        }
        if (state.nothingFits) {
            OutlinedTextField(
                value = state.categorySuggestion,
                onValueChange = actions::setCategorySuggestion,
                label = { Text(strings.suggestCategory) },
                supportingText = { Text(suggestionHint(state.suggestionProblem, strings)) },
                isError = state.suggestionProblem != null,
                enabled = editable,
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(text = strings.pickCategories, color = colors.muted, fontSize = WyrTypeScale.statLabel)
        }
        // When the points could not be read either, the one failure under Send says so, and its Try
        // again reads both.
        state.categoriesFailure?.takeIf { state.pointsFailure == null }?.let { failure ->
            Text(text = categoriesFailureText(failure, LocalStrings.current), color = MaterialTheme.colorScheme.error)
            OutlinedButton(
                onClick = tapped("submit.categories_try_again", onClick = actions::refresh),
                enabled = !state.isBusy,
            ) {
                Text(LocalStrings.current.tryAgain)
            }
        }

        // The server's refusal for points, or for a guest, says what the line under it would: one line
        // of it, not two.
        state.submitFailure
            ?.takeUnless { it.error == DomainError.NOT_ENOUGH_POINTS && state.tooFewPoints }
            ?.takeUnless { it.error == DomainError.ACCOUNT_REQUIRED && state.isGuest }
            ?.let { FailureText(it) }
        if (state.isGuest) {
            Text(text = strings.registerToSubmit, color = colors.primaryText)
        } else if (state.tooFewPoints) {
            Text(text = strings.notEnoughPoints, color = colors.primaryText)
        }
        Button(
            onClick = tapped("submit.send", onClick = actions::submit),
            enabled = state.canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val shared = LocalStrings.current
            PointsText(
                template = shared.accountScreens.send,
                points = state.submissionCost,
                spoken = sendText(shared, state.submissionCost),
            )
        }
        RulesLine()
        if (state.isBusy) {
            LinearProgressIndicator(color = colors.headingAccent, modifier = Modifier.fillMaxWidth())
        }
        state.pointsFailure?.let { failure ->
            FailureText(failure)
            OutlinedButton(onClick = tapped("submit.try_again", onClick = actions::refresh), enabled = !state.isBusy) {
                Text(LocalStrings.current.tryAgain)
            }
        }
    }
}

/**
 * The one short line under Send (CLAUDE.md §8d, *Submitting*): sending a question accepts the question
 * rules, the noun a link to the site's terms page ([SiteLinksLine]), which Google Play asks of a game
 * whose players post: a player registered by Play Games alone never saw the Register form's terms line.
 */
@Composable
private fun RulesLine() {
    val strings = LocalStrings.current.accountScreens.rulesLine
    SiteLinksLine(line = strings.line, links = listOf(SiteLink(strings.rules, SitePage.TERMS, "submit.rules")))
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

/**
 * Send, and what sending costs, [cost], as a screen reader hears it: *Пошаљи · Поени: 1*. On the button
 * the cost is the coin and the number.
 */
internal fun sendText(
    strings: Strings,
    cost: Int,
): String = strings.accountScreens.send.fill(strings.points.fill(cost))

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

/** The rule a category suggestion is held to, or what is wrong with the one typed by it. */
internal fun suggestionHint(
    problem: OptionProblem?,
    strings: AccountStrings,
): String =
    when (problem) {
        OptionProblem.TOO_LONG -> strings.optionTooLong.fill(SubmissionRules.MAX_CATEGORY_SUGGESTION_LENGTH)
        OptionProblem.NOT_ONE_LINE -> strings.optionNotOneLine
        else -> strings.suggestionRule.fill(SubmissionRules.MAX_CATEGORY_SUGGESTION_LENGTH)
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

        DomainError.ACCOUNT_REQUIRED -> {
            strings.registerToSubmit
        }

        DomainError.SUBMISSIONS_BLOCKED -> {
            strings.submissionsBlocked
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
