package io.ntole.wyr.admin.moderation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import io.ntole.wyr.admin.theme.AdminDimens

/**
 * Who wrote the question [questionId], on [from], and Block author and Unblock author for a question
 * with one (CLAUDE.md §8d, *Moderation*, *Authors*). The server says whether an author is blocked only
 * in answer to a block or an unblock, so an author it has not answered for offers both; one it has
 * offers the one that changes where they stand.
 */
@Composable
fun AuthorControls(
    authorId: String?,
    isSeed: Boolean,
    questionId: String,
    from: Screen,
    state: ModerationState,
    actions: ModerationActions,
) {
    val blocked = authorId?.let { state.authors[it] }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(AdminDimens.spaceSm),
        verticalArrangement = Arrangement.spacedBy(AdminDimens.spaceXs),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = authorLabelOf(authorId, isSeed, blocked), style = MaterialTheme.typography.labelLarge)
        if (authorId == null) return@FlowRow
        if (blocked != true) {
            OutlinedButton(onClick = { actions.askToBlock(authorId, questionId, from) }, enabled = state.canSend) {
                val blocking = state.running == Running(Action.BLOCK_AUTHOR, questionId)
                Text(if (blocking) "Blocking..." else "Block author...")
            }
        }
        if (blocked != false) {
            TextButton(onClick = { actions.unblock(authorId, questionId, from) }, enabled = state.canSend) {
                val unblocking = state.running == Running(Action.UNBLOCK_AUTHOR, questionId)
                Text(if (unblocking) "Unblocking..." else "Unblock author")
            }
        }
    }
}
