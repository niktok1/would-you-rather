package io.ntole.wyr.admin.moderation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.ntole.wyr.admin.theme.AdminDimens
import io.ntole.wyr.core.domain.moderation.RejectionReason
import io.ntole.wyr.core.domain.question.Category

/**
 * Approve and Reject for the pending question [questionId], filed under [authorsCategories]: the
 * categories to approve it under in place of the author's, none keeping theirs, and the reason to
 * reject it with. Reject stays off until the reason is one the server accepts.
 */
@Composable
fun DecisionControls(
    questionId: String,
    authorsCategories: Set<Category>,
    state: ModerationState,
    actions: ModerationActions,
) {
    val draft = state.draftOf(questionId)
    val reason = draft.reason
    val reasonRefused = reason.isNotBlank() && state.rejectionOf(questionId) == null

    Column(verticalArrangement = Arrangement.spacedBy(AdminDimens.spaceXs)) {
        Text(
            text = approvalOf(draft.categories, authorsCategories),
            style = MaterialTheme.typography.labelLarge,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AdminDimens.spaceSm),
            verticalArrangement = Arrangement.spacedBy(AdminDimens.spaceXs),
        ) {
            Category.selectable.forEach { category ->
                FilterChip(
                    selected = category in draft.categories,
                    onClick = { actions.toggleApprovalCategory(questionId, category) },
                    label = { Text(category.name) },
                )
            }
        }
        // One line, as the server holds a reason to one (provisional, CLAUDE.md §8b).
        OutlinedTextField(
            value = reason,
            onValueChange = { actions.setReason(questionId, it) },
            label = { Text("Reason to reject") },
            supportingText = {
                Text(
                    if (reasonRefused) {
                        "Not a reason the server takes: one line of at most ${RejectionReason.MAX_LENGTH} characters."
                    } else {
                        "Shown to the author. One line, at most ${RejectionReason.MAX_LENGTH} characters."
                    },
                )
            },
            isError = reasonRefused,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AdminDimens.spaceSm)) {
            Button(onClick = { actions.approve(questionId) }, enabled = state.canSend) {
                Text(if (state.running == Running(Action.APPROVE, questionId)) "Approving..." else "Approve")
            }
            OutlinedButton(
                onClick = { actions.reject(questionId) },
                enabled = state.canSend && state.rejectionOf(questionId) != null,
            ) {
                Text(if (state.running == Running(Action.REJECT, questionId)) "Rejecting..." else "Reject")
            }
        }
    }
}

/** What an approval with [picked] files the question under: the author's own when none are picked. */
fun approvalOf(
    picked: Set<Category>,
    authorsCategories: Set<Category>,
): String =
    if (picked.isEmpty()) {
        "Approve under the author's categories: ${namesOf(authorsCategories)}"
    } else {
        "Approve under ${namesOf(picked)}, in place of the author's"
    }
