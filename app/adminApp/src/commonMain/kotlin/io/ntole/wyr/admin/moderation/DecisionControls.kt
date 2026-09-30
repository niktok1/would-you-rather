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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.ntole.wyr.admin.theme.AdminDimens
import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.category.CategoryRules
import io.ntole.wyr.core.domain.moderation.RejectionReason

/**
 * Approve and Reject for the pending question [questionId], filed under [authorsCategories], on
 * [screen]: the categories to approve it under in place of the author's, picked from every category
 * the server listed, none keeping theirs, and the reason to reject it with, typed or one of
 * [READY_REASONS] picked and edited. Reject stays off until the reason is one the server accepts. What is picked and typed is the question's own, the same on
 * either screen.
 *
 * A question its author filed under none, saying none fitted, is approved only once a category is
 * picked, and [suggestion], the category they suggested, can be made a category here, which is then
 * picked (CLAUDE.md §8d, *Categories*, *Nothing fits*).
 */
@Composable
fun DecisionControls(
    questionId: String,
    authorsCategories: Set<String>,
    suggestion: String?,
    screen: Screen,
    state: ModerationState,
    actions: ModerationActions,
) {
    val draft = state.draftOf(questionId)
    val reason = draft.reason
    val reasonRefused = reason.isNotBlank() && state.rejectionOf(questionId) == null
    val known = state.categories.categories

    Column(verticalArrangement = Arrangement.spacedBy(AdminDimens.spaceXs)) {
        suggestion?.let { suggested ->
            Text(text = "The author suggests a category: $suggested", style = MaterialTheme.typography.bodyMedium)
        }
        val making = draft.newCategory
        if (suggestion != null && making == null) {
            OutlinedButton(
                onClick = { actions.startCategoryFromSuggestion(questionId, suggestion) },
                enabled = !state.isBusy,
            ) {
                Text("New category from the suggestion...")
            }
        }
        if (making != null) {
            NameFields(making, onChange = { actions.editCategoryFromSuggestion(questionId, it) })
            OutlinedTextField(
                value = making.id,
                onValueChange = { actions.editCategoryFromSuggestion(questionId, making.copy(id = it)) },
                label = { Text("Id, or blank") },
                supportingText = { Text(idHintOf(making.id)) },
                isError = making.id.isNotBlank() && !CategoryRules.isId(making.id),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AdminDimens.spaceSm)) {
                Button(
                    onClick = { actions.saveCategoryFromSuggestion(questionId, screen) },
                    enabled = state.canSend && making.isValid,
                ) {
                    Text(
                        if (state.running == Running(Action.ADD_CATEGORY, questionId)) "Adding..." else "Add and pick",
                    )
                }
                TextButton(onClick = { actions.cancelCategoryFromSuggestion(questionId) }) { Text("Cancel") }
            }
        }
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
        ReadyReasonChips(reason) { ready -> actions.setReason(questionId, ready) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AdminDimens.spaceSm)) {
            Button(
                onClick = { actions.approve(questionId, screen) },
                enabled = state.canApprove(questionId, authorsCategories),
            ) {
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
    when {
        picked.isNotEmpty() && authorsCategories.isEmpty() -> "Approve under ${namesOf(picked, known)}"
        picked.isNotEmpty() -> "Approve under ${namesOf(picked, known)}, in place of the author's"
        authorsCategories.isEmpty() -> "The author found no category fitting: pick one, or add one, to approve"
        else -> "Approve under the author's categories: ${namesOf(authorsCategories, known)}"
    }
