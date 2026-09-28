package io.ntole.wyr.admin.moderation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.ntole.wyr.admin.theme.AdminDimens
import io.ntole.wyr.admin.theme.AdminType
import io.ntole.wyr.core.domain.moderation.AccountRef

/**
 * Deleting a player's account on their request (CLAUDE.md §8a, *Deleting an account*, *By a
 * moderator*): one field for the username or the account id the player sent, which the game shows on
 * its About screen, and Delete account..., which asks first.
 */
@Composable
fun AccountsScreen(
    state: ModerationState,
    actions: ModerationActions,
    modifier: Modifier = Modifier,
) {
    val accounts = state.accounts
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AdminDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(AdminDimens.spaceSm),
    ) {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(AdminDimens.spaceMd),
                verticalArrangement = Arrangement.spacedBy(AdminDimens.spaceSm),
            ) {
                Text(text = "Delete an account on request", style = MaterialTheme.typography.titleMedium)
                Text(
                    text =
                        "For a player who asked by email. A guest, or a player signed in with Play Games " +
                            "alone, has no username: they send the account id the game's About screen shows.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = accounts.typed,
                    onValueChange = actions::setAccountToDelete,
                    label = { Text("Username or account id") },
                    supportingText = { Text(accountHintOf(accounts.named)) },
                    singleLine = true,
                    textStyle = AdminType.code,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = actions::askToDeleteAccount,
                    enabled = state.canSend && accounts.named != null,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(if (state.running?.action == Action.DELETE_ACCOUNT) "Deleting..." else "Delete account...")
                }
                accounts.failure?.let { FailureLine(it) }
                accounts.outcomes.notice?.let { NoticeLine(it) }
            }
        }
    }
}

/** The account [account] names, as a line of text says it. */
fun accountLineOf(account: AccountRef): String =
    when (account) {
        is AccountRef.Username -> "username ${account.username}"
        is AccountRef.Id -> "account id ${account.accountId}"
    }

/** What the field's text will be sent as, or what to type. */
fun accountHintOf(account: AccountRef?): String =
    when (account) {
        null -> "A username, as the player logs in with it, or an account id."
        is AccountRef.Username -> "Sent as a username: ${account.username}."
        is AccountRef.Id -> "Sent as an account id, as it is no username."
    }

/** What the deletion's dialog says will go, and that it is for good. */
fun deleteWarningOf(account: AccountRef): String =
    "The account of ${accountLineOf(account)} is deleted for good: its username and password, its sessions " +
        "on every device, its votes, skips, reactions, reports and hides, and its questions not approved. " +
        "Its approved questions stay, with nobody as their author, and each like it held is taken back. " +
        "This cannot be undone."
