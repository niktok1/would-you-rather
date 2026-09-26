package io.ntole.wyr.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import io.ntole.wyr.analytics.tapped
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.language.AccountStrings
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.language.fill
import io.ntole.wyr.theme.WyrIcons
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale

/**
 * My questions, on the Account screen (CLAUDE.md §8d, *The Account screen*, *Submitting*): the
 * questions the player submitted, newest first, as a table, each with its two options, where it
 * stands, and how many players like it, dislike it and have answered it, and a last row adding them
 * up. With none, the table stays, with the way to ask the first. New question, which [onNewQuestion]
 * answers by opening the Submit screen's form, is a registered player's: a guest is told to register
 * first, in the empty table or, with questions of before, under the heading. The list is read with the
 * player, each time the screen is shown.
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
    // Only a registered player submits (CLAUDE.md §8d, *Submitting*); the server refuses a guest too.
    val canAsk = state.stats?.username != null

    Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceSm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = strings.myQuestions,
                color = colors.primaryText,
                fontSize = WyrTypeScale.sectionTitle,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = tapped("my_questions.new_question", onClick = onNewQuestion), enabled = canAsk) {
                Text(strings.newQuestion)
            }
        }
        val submissions = state.submissions
        // With none listed, the empty table says it (below).
        if (!canAsk && !submissions.isNullOrEmpty()) {
            Text(text = strings.registerToSubmit, color = colors.muted, fontSize = WyrTypeScale.statLabel)
        }

        val failure = state.listFailure
        if (submissions != null) {
            QuestionsTable(submissions, canAsk = canAsk, onNewQuestion = onNewQuestion)
        } else if (failure == null) {
            // With no failure a read is on its way: every read of the player reads the list too.
            CircularProgressIndicator(color = colors.headingAccent)
        }
        // When the stats could not be read either, the one failure above them says so, and its Try
        // again reads both.
        if (failure != null && state.failure?.action != AccountAction.LOAD) {
            FailureText(failure)
            OutlinedButton(
                onClick = tapped("my_questions.try_again", onClick = actions::refresh),
                enabled = !state.isBusy,
            ) {
                Text(LocalStrings.current.tryAgain)
            }
        }
    }
}

/**
 * The table: a heading row, the question and a column each for its likes, its dislikes and its
 * answers, headed by a thumb up, a thumb down and two players, which a screen reader names; a row for
 * each question; and a last row adding the columns up. With no question, one row: the way to ask the
 * first, a registered player's ([canAsk]), or for a guest, who cannot ask, that they register first.
 */
@Composable
private fun QuestionsTable(
    submissions: List<Submission>,
    canAsk: Boolean,
    onNewQuestion: () -> Unit,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val strings = LocalStrings.current.accountScreens

    Surface(
        color = colors.surface,
        shape = RoundedCornerShape(dimens.radiusCard),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = dimens.spaceMd, vertical = dimens.spaceSm)) {
            TableRow {
                Text(
                    text = strings.question,
                    color = colors.muted,
                    fontSize = WyrTypeScale.statLabel,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                HeadingCell(WyrIcons.ThumbUp, strings.likes)
                HeadingCell(WyrIcons.ThumbDown, strings.dislikes)
                HeadingCell(WyrIcons.Players, strings.answers)
            }
            HorizontalDivider(color = colors.orPillBackground)

            if (submissions.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = dimens.spaceXs),
                ) {
                    if (canAsk) {
                        TextButton(onClick = tapped("my_questions.first_question", onClick = onNewQuestion)) {
                            Text(strings.firstQuestion)
                        }
                    } else {
                        Text(
                            text = strings.registerToSubmit,
                            color = colors.muted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = dimens.spaceSm),
                        )
                    }
                }
            } else {
                submissions.forEach { submission ->
                    QuestionRow(submission, strings)
                    HorizontalDivider(color = colors.orPillBackground)
                }
                TotalRow(submissions, strings)
            }
        }
    }
}

/** A question's row: its options and where it stands, then its numbers, read out as one. */
@Composable
private fun QuestionRow(
    submission: Submission,
    strings: AccountStrings,
) {
    val colors = WyrThemeAccessors.colors
    val counts = countsOf(submission)
    val options =
        buildAnnotatedString {
            append(submission.optionA)
            withStyle(SpanStyle(color = colors.muted)) { append(" ${strings.or} ") }
            append(submission.optionB)
        }

    TableRow(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = options, color = colors.primaryText)
            Text(
                text = statusText(submission, strings),
                color = colors.headingAccent,
                fontSize = WyrTypeScale.statLabel,
                fontWeight = FontWeight.Bold,
            )
        }
        NumberCell(counts?.likes, strings.likes)
        NumberCell(counts?.dislikes, strings.dislikes)
        NumberCell(counts?.answers, strings.answers)
    }
}

/** The last row: every question's likes, dislikes and answers, added up. */
@Composable
private fun TotalRow(
    submissions: List<Submission>,
    strings: AccountStrings,
) {
    val colors = WyrThemeAccessors.colors
    val total = totalOf(submissions)

    TableRow(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text(
            text = strings.total,
            color = colors.primaryText,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        NumberCell(total.likes, strings.likes, bold = true)
        NumberCell(total.dislikes, strings.dislikes, bold = true)
        NumberCell(total.answers, strings.answers, bold = true)
    }
}

/** One row of the table, its cells side by side and centred on one another. */
@Composable
private fun TableRow(
    modifier: Modifier = Modifier,
    cells: @Composable RowScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(vertical = WyrThemeAccessors.dimens.spaceXs),
        content = cells,
    )
}

/** A number column's heading: an icon, named for a screen reader. */
@Composable
private fun HeadingCell(
    icon: ImageVector,
    name: String,
) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.width(WyrThemeAccessors.dimens.tableNumberWidth)) {
        Icon(
            imageVector = icon,
            contentDescription = name,
            tint = WyrThemeAccessors.colors.muted,
            modifier = Modifier.size(WyrThemeAccessors.dimens.tableIconSize),
        )
    }
}

/**
 * A number of a question, [heading]'s column's: the number, which a screen reader hears after the
 * column's name, or for a question never served, which has none, [NOT_SERVED], which it does not hear.
 */
@Composable
private fun NumberCell(
    value: Int?,
    heading: String,
    bold: Boolean = false,
) {
    val colors = WyrThemeAccessors.colors

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .width(WyrThemeAccessors.dimens.tableNumberWidth)
                .clearAndSetSemantics { if (value != null) contentDescription = "$heading: $value" },
    ) {
        Text(
            text = value?.toString() ?: NOT_SERVED,
            color = if (value == null) colors.muted else colors.primaryText,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** A question's likes, dislikes and the players who answered it, as the server counted them. */
internal data class QuestionCounts(
    val likes: Int,
    val dislikes: Int,
    val answers: Int,
)

/**
 * What [submission] holds, or null for a question never served, pending or rejected, which holds
 * nothing and shows [NOT_SERVED]. A retired one keeps what it had (CLAUDE.md §8d, *Moderation*).
 */
internal fun countsOf(submission: Submission): QuestionCounts? =
    when (submission.status) {
        SubmissionStatus.APPROVED, SubmissionStatus.RETIRED -> {
            QuestionCounts(submission.likeCount, submission.dislikeCount, submission.answerCount)
        }

        SubmissionStatus.PENDING, SubmissionStatus.REJECTED, SubmissionStatus.OTHER -> {
            null
        }
    }

/** Every question's likes, dislikes and answers, added up: what [submissions] hold together. */
internal fun totalOf(submissions: List<Submission>): QuestionCounts {
    val counts = submissions.mapNotNull(::countsOf)
    return QuestionCounts(
        likes = counts.sumOf { it.likes },
        dislikes = counts.sumOf { it.dislikes },
        answers = counts.sumOf { it.answers },
    )
}

/** The table's number for a question never served, which holds nothing: the same in every language. */
internal const val NOT_SERVED = "–"

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
