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
import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.moderation.RejectionReason

/**
 * Approve and Reject for the pending question [questionId], filed under [authorsCategories], on
 * [screen]: the categories to approve it under in place of the author's, picked from every category
 * the server listed, none keeping theirs, and the reason to reject it with. Reject stays off until
 * the reason is one the server accepts. What is picked and typed is the question's own, the same on
 * either screen.
 */
@Composable
fun DecisionControls(
    questionId: String,
    authorsCategories: Set<String>,
    screen: Screen,
    state: ModerationState,
    actions: ModerationActions,
) {
    val draft = state.draftOf(questionId)
    val reason = draft.reason
    val reasonRefused = reason.isNotBlank() && state.rejectionOf(questionId) == null
    val known = state.categories.categories

    Column(verticalArrangement = Arrangement.spacedBy(AdminDimens.spaceXs)) {
        Text(
            text = approvalOf(draft.categories, authorsCategories, known),
            style = MaterialTheme.typography.labelLarge,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AdminDimens.spaceSm),
            verticalArrangement = Arrangement.spacedBy(AdminDimens.spaceXs),
        ) {
            known.orEmpty().forEach { category ->
                FilterChip(
                    selected = category.id in draft.categories,
                    onClick = { actions.toggleApprovalCategory(questionId, category.id) },
                    label = { Text(nameOf(category.id, known)) },
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
            Button(onClick = { actions.approve(questionId, screen) }, enabled = state.canSend) {
                Text(if (state.running == Running(Action.APPROVE, questionId)) "Approving..." else "Approve")
            }
            OutlinedButton(
                onClick = { actions.reject(questionId, screen) },
                enabled = state.canSend && state.rejectionOf(questionId) != null,
            ) {
                Text(if (state.running == Running(Action.REJECT, questionId)) "Rejecting..." else "Reject")
            }
        }
    }
}

/**
 * What an approval with [picked] files the question under: the author's own when none are picked,
 * each named as [known] lists it.
 */
fun approvalOf(
    picked: Set<String>,
    authorsCategories: Set<String>,
    known: List<Category>?,
): String =
    if (picked.isEmpty()) {
        "Approve under the author's categories: ${namesOf(authorsCategories, known)}"
    } else {
        "Approve under ${namesOf(picked, known)}, in place of the author's"
    }
