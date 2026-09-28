package io.ntole.wyr.about

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import io.ntole.wyr.analytics.tapped
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.theme.WyrIcons
import io.ntole.wyr.theme.WyrThemeAccessors

/**
 * The Statistics switch (CLAUDE.md §8g), on the About screen, dimmed: whether the player lets the game send analytics,
 * [on], which a tap anywhere on it turns the other way, [onChange]. Its word and the switch are one
 * control, which a screen reader hears as the word, a switch, and on or off; the info icon beside the
 * word is a button of its own, which opens a dialog saying what is sent and what never is.
 */
@Composable
fun StatisticsSwitch(
    on: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val colors = WyrThemeAccessors.colors
    val strings = LocalStrings.current.accountScreens
    val toggle = tapped("account.statistics") { onChange(!on) }
    var explaining by rememberSaveable { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = on, role = Role.Switch, onValueChange = { toggle() })
                .minimumInteractiveComponentSize(),
    ) {
        Text(text = strings.statistics, color = colors.muted, maxLines = 1)
        IconButton(onClick = tapped("account.statistics_info") { explaining = true }) {
            Icon(
                imageVector = WyrIcons.Info,
                contentDescription = strings.aboutStatistics,
                tint = colors.muted,
                modifier = Modifier.size(WyrThemeAccessors.dimens.tableIconSize),
            )
        }
        Spacer(Modifier.weight(1f))
        // No click of its own: the row's toggleable is the one. Muted, as the rest of the options are.
        Switch(
            checked = on,
            onCheckedChange = null,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = colors.surface,
                    checkedTrackColor = colors.muted,
                    checkedBorderColor = colors.muted,
                    uncheckedThumbColor = colors.muted,
                    uncheckedTrackColor = colors.pageBackground,
                    uncheckedBorderColor = colors.muted,
                ),
        )
    }
    if (explaining) {
        AlertDialog(
            onDismissRequest = { explaining = false },
            text = { Text(strings.statisticsInfo) },
            // The theme's, not Material's own container and text colours (CLAUDE.md §5b).
            containerColor = colors.surface,
            textContentColor = colors.primaryText,
            confirmButton = {
                TextButton(onClick = tapped("account.statistics_info_ok") { explaining = false }) {
                    Text(strings.ok)
                }
            },
        )
    }
}
