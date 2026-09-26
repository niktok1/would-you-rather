package io.ntole.wyr.admin.moderation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.ntole.wyr.admin.theme.AdminDimens
import io.ntole.wyr.admin.theme.AdminType
import io.ntole.wyr.core.domain.moderation.ReportReason
import io.ntole.wyr.core.domain.moderation.ReportedQuestion
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The questions players reported (CLAUDE.md §8d, *Moderation*, *Reports*), most reported first: each
 * with how many report it and why, its options, categories, status, votes and reactions, and what can
 * be done with it: Dismiss reports, which takes it off the list until a player reports it again,
 * Retire, which asks first, or Restore, and Block author, which asks first, or Unblock author. The
 * reports are read again after every dismissal and block.
 */
@Composable
fun ReportsScreen(
    state: ModerationState,
    actions: ModerationActions,
    modifier: Modifier = Modifier,
) {
    val list = state.reports
    val reports = list.reports
    // Ages are counted from when the reports were last read, so they hold still between reads.
    val now = remember(reports) { Clock.System.now() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(AdminDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(AdminDimens.spaceMd),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(AdminDimens.spaceSm)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AdminDimens.spaceMd),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = actions::loadReports, enabled = state.canSend) {
                        Text(if (reports == null) "Load reports" else "Reload")
                    }
                    Text(text = reportsSummaryOf(reports), style = MaterialTheme.typography.bodyMedium)
                }
                state.categories.failure?.let { CategoriesFailure(it) }
                list.failure?.let { FailureLine(it) }
                list.outcomes.notice?.let { NoticeLine(it) }
                UnlistedFailures(list.outcomes.failures, reports.orEmpty().map { it.question.id }.toSet())
            }
        }
        items(reports.orEmpty(), key = { it.question.id }) { reported ->
            ReportCard(reported, now, state, actions)
        }
    }
}

@Composable
private fun ReportCard(
    reported: ReportedQuestion,
    now: Instant,
    state: ModerationState,
    actions: ModerationActions,
) {
    val question = reported.question
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AdminDimens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(AdminDimens.spaceSm),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(AdminDimens.spaceSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusBadge(question.status)
                Text(text = reportCountOf(reported.reportCount), style = MaterialTheme.typography.titleSmall)
            }
            Text(text = reasonsOf(reported.reasons), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "Last reported ${ageOf(reported.lastReportedAt, now)} · ${shownTime(reported.lastReportedAt)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(text = "A: ${question.optionA}", style = MaterialTheme.typography.titleMedium)
            Text(text = "B: ${question.optionB}", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Categories: ${namesOf(question.categories, state.categories.categories)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "${tallyOf(question)} · ${likesOf(question.likeCount)} · ${dislikesOf(question.dislikeCount)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(text = question.id, style = AdminType.code, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AuthorControls(question.authorId, question.isSeed, question.id, Screen.REPORTS, state, actions)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AdminDimens.spaceSm),
                verticalArrangement = Arrangement.spacedBy(AdminDimens.spaceXs),
            ) {
                OutlinedButton(onClick = { actions.dismiss(question.id) }, enabled = state.canSend) {
                    val dismissing = state.running == Running(Action.DISMISS_REPORTS, question.id)
                    Text(if (dismissing) "Dismissing..." else "Dismiss reports")
                }
                MoveButton(question, Screen.REPORTS, state, actions)
            }
            state.reports.outcomes.failures[question.id]
                ?.let { FailureLine(it.failure) }
        }
    }
}

/**
 * How many reported questions are listed, or that none has been read. A list as long as a read can be
 * ([isFull]) is only the most reported of them, and says so.
 */
fun reportsSummaryOf(reports: List<ReportedQuestion>?): String =
    when {
        reports == null -> "Not read yet."
        reports.isEmpty() -> "Nothing reported."
        isFull(reports) -> "The ${reports.size} most reported; more may be, read in as these are dismissed."
        reports.size == 1 -> "1 reported question."
        else -> "${reports.size} reported questions, most reported first."
    }

/** How many players report a question now. */
fun reportCountOf(count: Int): String = if (count == 1) "Reported by 1 player" else "Reported by $count players"

/** Each reason given and how many gave it, most given first. */
fun reasonsOf(reasons: Map<ReportReason, Int>): String =
    if (reasons.isEmpty()) {
        "No reason counted."
    } else {
        reasons.entries.joinToString(" · ") { (reason, count) -> "${reasonLabelOf(reason)} $count" }
    }

/** How the moderator reads [reason]. */
fun reasonLabelOf(reason: ReportReason): String =
    when (reason) {
        ReportReason.OFFENSIVE -> "Offensive"
        ReportReason.REAL_PERSON -> "Real person"
        ReportReason.SPAM -> "Spam"
        ReportReason.NOT_A_CHOICE -> "Not a choice"
        ReportReason.OTHER -> "Other"
        ReportReason.UNKNOWN -> "A reason this build cannot name"
    }
