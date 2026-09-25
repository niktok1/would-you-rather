package io.ntole.wyr.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.language.AccountStrings
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.language.fill
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale

/**
 * My questions, on the Account screen (CLAUDE.md §8d, *The Account screen*, *Submitting*): the
 * questions the player submitted, newest first, each with its two options and where it stands, and
 * New question, which [onNewQuestion] answers by opening the Submit screen's form. The list is read
 * with the player, each time the screen is shown.
 */
@Composable
internal fun MyQuestions(
    state: AccountState,
    actions: AccountActions,
    onNewQuestion: () -> Unit,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val strings = LocalStrings.current.accountScreens

    Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceSm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = strings.myQuestions,
                color = colors.primaryText,
                fontSize = WyrTypeScale.sectionTitle,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onNewQuestion) { Text(strings.newQuestion) }
        }

        val submissions = state.submissions
        val failure = state.listFailure
        when {
            // With no failure a read is on its way: every read of the player reads the list too.
            submissions == null -> {
                if (failure == null) CircularProgressIndicator(color = colors.headingAccent)
            }

            submissions.isEmpty() -> {
                Text(text = strings.noQuestions, color = colors.muted)
            }

            else -> {
                submissions.forEach { QuestionCard(it, strings) }
            }
        }
        if (failure != null) {
            FailureText(failure)
            OutlinedButton(onClick = actions::refresh, enabled = !state.isBusy) { Text(strings.tryAgain) }
        }
    }
}

@Composable
private fun QuestionCard(
    submission: Submission,
    strings: AccountStrings,
) {
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
            Text(
                text = statusText(submission, strings),
                color = colors.headingAccent,
                fontSize = WyrTypeScale.statLabel,
                fontWeight = FontWeight.Bold,
            )
            Text(text = submission.optionA, color = colors.primaryText)
            Text(text = strings.or, color = colors.muted, fontSize = WyrTypeScale.statLabel)
            Text(text = submission.optionB, color = colors.primaryText)
        }
    }
}

/** Where [submission] stands with the moderator, in a word, a rejected one with the moderator's reason. */
internal fun statusText(
    submission: Submission,
    strings: AccountStrings,
): String =
    when (submission.status) {
        SubmissionStatus.PENDING -> {
            strings.pending
        }

        SubmissionStatus.APPROVED -> {
            strings.approved
        }

        SubmissionStatus.REJECTED -> {
            submission.rejectionReason?.let { strings.rejectedBecause.fill(it) }
                ?: strings.rejected
        }

        SubmissionStatus.RETIRED -> {
            strings.retired
        }

        SubmissionStatus.OTHER -> {
            strings.unknownStatus
        }
    }
