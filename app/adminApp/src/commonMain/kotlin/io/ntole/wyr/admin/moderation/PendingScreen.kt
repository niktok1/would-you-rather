package io.ntole.wyr.admin.moderation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.ntole.wyr.admin.theme.AdminDimens
import io.ntole.wyr.admin.theme.AdminType
import io.ntole.wyr.core.domain.moderation.ModerationRepository
import io.ntole.wyr.core.domain.submission.Submission
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The pending queue (CLAUDE.md §8d, *Moderation*): every submission waiting for a decision, oldest
 * first, each with its options, categories and age, and Approve and Reject under it. The queue is
 * read again after every decision, so a decided one leaves it.
 */
@Composable
fun PendingScreen(
    state: ModerationState,
    actions: ModerationActions,
    modifier: Modifier = Modifier,
) {
    val queue = state.pending
    val submissions = queue.submissions
    // Ages are counted from when the queue was last read, so they hold still between reads.
    val now = remember(submissions) { Clock.System.now() }

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
                    Button(onClick = actions::loadPending, enabled = state.canSend) {
                        Text(if (submissions == null) "Load pending" else "Reload")
                    }
                    Text(text = queueSummaryOf(submissions), style = MaterialTheme.typography.bodyMedium)
                }
                state.categories.failure?.let { CategoriesFailure(it) }
                queue.failure?.let { FailureLine(it) }
                queue.outcomes.notice?.let { NoticeLine(it) }
                UnlistedFailures(queue.outcomes.failures, submissions.orEmpty().map { it.id }.toSet())
            }
        }
        items(submissions.orEmpty(), key = { it.id }) { submission ->
            PendingCard(submission, now, state, actions)
        }
    }
}

@Composable
private fun PendingCard(
    submission: Submission,
    now: Instant,
    state: ModerationState,
    actions: ModerationActions,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AdminDimens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(AdminDimens.spaceSm),
        ) {
            Text(
                text = "Submitted ${ageOf(submission.submittedAt, now)} · ${shownTime(submission.submittedAt)}",
                style = MaterialTheme.typography.labelMedium,
            )
            Text(text = "A: ${submission.optionA}", style = MaterialTheme.typography.titleMedium)
            Text(text = "B: ${submission.optionB}", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Categories: ${namesOf(submission.categories, state.categories.categories)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(text = submission.id, style = AdminType.code, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DecisionControls(submission.id, submission.categories, Screen.PENDING, state, actions)
            state.pending.outcomes.failures[submission.id]
                ?.let { FailureLine(it.failure) }
        }
    }
}

/**
 * How many are waiting, or that the queue has not been read. A queue read as long as a read can be
 * ([isFull]) is only the oldest of those waiting, and says so.
 */
fun queueSummaryOf(submissions: List<Submission>?): String =
    when {
        submissions == null -> "Not read yet."
        submissions.isEmpty() -> "Nothing waiting."
        isFull(submissions) -> "The oldest ${submissions.size}; more may be waiting, read in as these are decided."
        submissions.size == 1 -> "1 waiting."
        else -> "${submissions.size} waiting, oldest first."
    }

/**
 * Whether [submissions] is as many as one read of the queue lists ([ModerationRepository.PAGE_SIZE]),
 * so more may be waiting behind them.
 */
fun isFull(submissions: List<Submission>): Boolean = submissions.size >= ModerationRepository.PAGE_SIZE
