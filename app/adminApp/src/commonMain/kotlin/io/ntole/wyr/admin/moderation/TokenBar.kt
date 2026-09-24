package io.ntole.wyr.admin.moderation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.ntole.wyr.admin.theme.AdminDimens

/**
 * The admin token, typed and masked, with Lock beside it, which forgets it and everything read with
 * it. A bar under the field runs while an action is in flight.
 */
@Composable
fun TokenBar(
    state: ModerationState,
    actions: ModerationActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(AdminDimens.spaceXs)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(AdminDimens.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Masked, and a password to the keyboard, so no keyboard learns it as a word. Its value lives
            // in the ViewModel, not rememberSaveable, whose saved state can be written to disk.
            OutlinedTextField(
                value = state.adminToken.text,
                onValueChange = actions::setAdminToken,
                label = { Text("Admin token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.weight(1f),
            )
            // Always on: whatever is typed, shown or in flight, Lock forgets it.
            OutlinedButton(onClick = actions::lock) { Text("Lock") }
        }
        Text(
            text = tokenStatusOf(state.adminToken.text),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.isBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}
