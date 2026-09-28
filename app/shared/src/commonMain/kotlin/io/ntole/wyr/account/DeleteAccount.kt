package io.ntole.wyr.account

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.ntole.wyr.analytics.tapped
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.theme.WyrThemeAccessors

/**
 * Deleting the account, at the bottom of the About screen (CLAUDE.md §8d, *About*; §8a, *Deleting an
 * account*): a quiet button, which asks in a dialog of one line whether everything is to go for good,
 * and only then deletes; why it failed, above it. Nothing while [AccountState.offersDeletion] is not
 * so, but a failure to show.
 */
@Composable
fun DeleteAccount(
    state: AccountState,
    actions: AccountActions,
    modifier: Modifier = Modifier,
) {
    val colors = WyrThemeAccessors.colors
    val strings = LocalStrings.current.accountScreens.deleteAccount
    var confirming by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier) {
        FailureOf(state, AccountAction.DELETE)
        if (state.offersDeletion) {
            TextButton(
                onClick = tapped("account.delete") { confirming = true },
                enabled = !state.isBusy,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.muted),
            ) {
                Text(strings.button)
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            text = { Text(strings.warning) },
            // The theme's, not Material's own container and text colours (CLAUDE.md §5b).
            containerColor = colors.surface,
            textContentColor = colors.primaryText,
            confirmButton = {
                TextButton(
                    onClick =
                        tapped("account.delete_confirm") {
                            confirming = false
                            actions.deleteAccount()
                        },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(strings.confirm)
                }
            },
            dismissButton = {
                TextButton(onClick = tapped("account.delete_cancel") { confirming = false }) {
                    Text(LocalStrings.current.cancel)
                }
            },
        )
    }
}
