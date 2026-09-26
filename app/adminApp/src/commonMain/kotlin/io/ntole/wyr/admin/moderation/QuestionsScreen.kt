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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.ntole.wyr.admin.theme.AdminDimens
import io.ntole.wyr.admin.theme.AdminType
import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.moderation.ModeratedQuestion
import io.ntole.wyr.core.domain.moderation.QuestionFilter
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Every question, seeds included, newest first (CLAUDE.md §8d, *Moderation*), at the statuses and
 * categories picked, a page at a time: each with its options, categories, status, where it came
 * from, its votes, likes and dislikes, its times and a rejected one's reason, its author with Block
 * author and Unblock author, and what can be done with it where it stands. Retire asks first.
 */
@Composable
fun QuestionsScreen(
    state: ModerationState,
    actions: ModerationActions,
    modifier: Modifier = Modifier,
) {
    val list = state.questions
    val questions = list.questions
    // Ages are counted from when the list was last read, so they hold still between reads.
    val now = remember(questions) { Clock.System.now() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(AdminDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(AdminDimens.spaceMd),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(AdminDimens.spaceSm)) {
                FilterRows(list.filter, state.categories.categories, enabled = !state.isBusy, actions)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AdminDimens.spaceMd),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = actions::loadQuestions, enabled = state.canSend) {
                        Text(if (questions == null) "Load" else "Reload")
                    }
                    Text(text = listSummaryOf(list), style = MaterialTheme.typography.bodyMedium)
                }
                state.categories.failure?.let { CategoriesFailure(it) }
                list.failure?.let { FailureLine(it) }
                list.outcomes.notice?.let { NoticeLine(it) }
                UnlistedFailures(list.outcomes.failures, questions.orEmpty().map { it.id }.toSet())
            }
        }
        items(questions.orEmpty(), key = { it.id }) { question ->
            QuestionCard(question, now, state, actions)
        }
        if (questions != null) {
            item {
                if (list.canLoadMore) {
                    OutlinedButton(onClick = actions::loadMore, enabled = state.canSend) {
                        Text(if (state.running?.action == Action.LOAD_MORE) "Loading..." else "Load more")
                    }
                } else if (questions.isNotEmpty()) {
                    Text(text = "End of the list.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * The statuses and categories to list, each chip adding its value, none picked being every one. The
 * categories are [known], every one the server listed.
 */
@Composable
private fun FilterRows(
    filter: QuestionFilter,
    known: List<Category>?,
    enabled: Boolean,
    actions: ModerationActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AdminDimens.spaceXs)) {
        Text(text = "Status: ${statusFilterOf(filter)}", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AdminDimens.spaceSm)) {
            LISTABLE_STATUSES.forEach { status ->
                FilterChip(
                    selected = status in filter.statuses,
                    onClick = { actions.toggleStatusFilter(status) },
                    label = { Text(statusLabelOf(status)) },
                    enabled = enabled,
                )
            }
        }
        Text(text = "Category: ${categoryFilterOf(filter, known)}", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AdminDimens.spaceSm)) {
            known.orEmpty().forEach { category ->
                FilterChip(
                    selected = category.id in filter.categories,
                    onClick = { actions.toggleCategoryFilter(category.id) },
                    label = { Text(nameOf(category.id, known)) },
                    enabled = enabled,
                )
            }
            if (filter != QuestionFilter()) {
                TextButton(onClick = actions::clearFilter, enabled = enabled) { Text("Show everything") }
            }
        }
    }
}

@Composable
private fun QuestionCard(
    question: ModeratedQuestion,
    now: Instant,
    state: ModerationState,
    actions: ModerationActions,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AdminDimens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(AdminDimens.spaceSm),
        ) {
            // Where it came from, a seed or its author, is the author line's to say, beside its buttons.
            StatusBadge(question.status)
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
            timesOf(question, now).forEach { line -> Text(text = line, style = MaterialTheme.typography.bodySmall) }
            question.rejectionReason?.let { reason ->
                Text(text = "Rejected because: $reason", style = MaterialTheme.typography.bodyMedium)
            }
            Text(text = question.id, style = AdminType.code, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AuthorControls(question.authorId, question.isSeed, question.id, Screen.QUESTIONS, state, actions)
            QuestionActions(question, state, actions)
            state.questions.outcomes.failures[question.id]
                ?.let { FailureLine(it.failure) }
        }
    }
}

/** What can be done with [question] where it stands: nothing, once rejected or unknown. */
@Composable
private fun QuestionActions(
    question: ModeratedQuestion,
    state: ModerationState,
    actions: ModerationActions,
) {
    if (question.status == SubmissionStatus.PENDING) {
        DecisionControls(question.id, question.categories, Screen.QUESTIONS, state, actions)
    } else {
        MoveButton(question, Screen.QUESTIONS, state, actions)
    }
}

/**
 * Retire for an approved [question], which asks first, and Restore for a retired one, from [from];
 * nothing for any other: a rejection is final, and a status this build cannot name has nothing this
 * build can do.
 */
@Composable
fun MoveButton(
    question: ModeratedQuestion,
    from: Screen,
    state: ModerationState,
    actions: ModerationActions,
) {
    when (question.status) {
        SubmissionStatus.APPROVED -> {
            OutlinedButton(onClick = { actions.askToRetire(question.id, from) }, enabled = state.canSend) {
                Text(if (state.running == Running(Action.RETIRE, question.id)) "Retiring..." else "Retire...")
            }
        }

        SubmissionStatus.RETIRED -> {
            Button(onClick = { actions.restore(question.id, from) }, enabled = state.canSend) {
                Text(if (state.running == Running(Action.RESTORE, question.id)) "Restoring..." else "Restore")
            }
        }

        SubmissionStatus.PENDING, SubmissionStatus.REJECTED, SubmissionStatus.OTHER -> {}
    }
}

/** Where [status] stands, in the colors of the scheme: approved and pending apart, rejected in red. */
@Composable
fun StatusBadge(status: SubmissionStatus) {
    val colors = MaterialTheme.colorScheme
    val (container, content) =
        when (status) {
            SubmissionStatus.APPROVED -> colors.primaryContainer to colors.onPrimaryContainer
            SubmissionStatus.PENDING -> colors.tertiaryContainer to colors.onTertiaryContainer
            SubmissionStatus.REJECTED -> colors.errorContainer to colors.onErrorContainer
            SubmissionStatus.RETIRED, SubmissionStatus.OTHER -> colors.surfaceVariant to colors.onSurfaceVariant
        }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Text(
            text = statusLabelOf(status),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = AdminDimens.spaceSm, vertical = AdminDimens.spaceXs),
        )
    }
}

/** How the list names [status]. */
fun statusLabelOf(status: SubmissionStatus): String =
    when (status) {
        SubmissionStatus.PENDING -> "Pending"
        SubmissionStatus.APPROVED -> "Approved"
        SubmissionStatus.REJECTED -> "Rejected"
        SubmissionStatus.RETIRED -> "Retired"
        SubmissionStatus.OTHER -> "Unknown status"
    }

/** The statuses [filter] lists, or that it lists every one. */
fun statusFilterOf(filter: QuestionFilter): String =
    if (filter.statuses.isEmpty()) "every status" else filter.statuses.joinToString(", ") { statusLabelOf(it) }

/** The categories [filter] lists, each named as [known] lists it, or that it lists every one. */
fun categoryFilterOf(
    filter: QuestionFilter,
    known: List<Category>?,
): String = if (filter.categories.isEmpty()) "every category" else namesOf(filter.categories, known)

/** How many are listed, whether more follow, or that nothing was read at this filter yet. */
fun listSummaryOf(list: QuestionList): String {
    val questions = list.questions ?: return "Not read at this filter yet."
    return when {
        questions.isEmpty() -> "No question matches."
        list.canLoadMore -> "${questions.size} listed, newest first, and more to load."
        else -> "${questions.size} listed, newest first: all of them."
    }
}

/** Every player's latest answer to [question], each side's count and share. */
fun tallyOf(question: ModeratedQuestion): String {
    val tally = question.tally
    if (!tally.hasVotes) return "No votes yet"
    return "Votes: A ${tally.votesA} (${tally.percentA}%), B ${tally.votesB} (${tally.percentB}%)"
}

fun likesOf(count: Int): String = if (count == 1) "1 like" else "$count likes"

fun dislikesOf(count: Int): String = if (count == 1) "1 dislike" else "$count dislikes"

/** When [question] was stored, reviewed and retired, as far as each has happened. */
fun timesOf(
    question: ModeratedQuestion,
    now: Instant,
): List<String> =
    buildList {
        val stored = if (question.isSeed) "Seeded" else "Submitted"
        add("$stored ${ageOf(question.submittedAt, now)} · ${shownTime(question.submittedAt)}")
        question.reviewedAt?.let { add("Reviewed ${shownTime(it)}") }
        question.retiredAt?.let { add("Retired ${shownTime(it)}") }
    }

/** What retiring [question] does, for the moderator to confirm it. */
fun retireWarningOf(question: ModeratedQuestion?): String {
    val named = question?.let { optionsOf(it.optionA, it.optionB) } ?: "This question"
    return "$named will be served to nobody, and nobody can vote on, skip, like or dislike it, until " +
        "it is restored. Its votes, its likes and dislikes and the points they earned all stay."
}
