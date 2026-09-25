package io.ntole.wyr.navigation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.theme.WyrIcons
import io.ntole.wyr.theme.WyrThemeAccessors

// The top bars (CLAUDE.md §8d, *Navigation*): one row of icon buttons above a screen, the way from it
// to the others, since only Android has a back of its own. Each is WyrDimens.topBarHeight high, the
// tab row's height before them, so the screen under one keeps the height it had under the tabs.

/** Home's: the account icon, top right. */
@Composable
fun HomeTopBar(onAccount: () -> Unit) {
    TopBar(end = { AccountButton(onAccount) })
}

/** Play's: home on the left, back to Home, and the account icon on the right. */
@Composable
fun PlayTopBar(
    onHome: () -> Unit,
    onAccount: () -> Unit,
) {
    TopBar(start = { IconAction(WyrIcons.Home, LocalStrings.current.home, onHome) }, end = { AccountButton(onAccount) })
}

/** Account's: the back arrow, and for now the way to the Submit screen (§8d, *The Submit screen*). */
@Composable
fun AccountTopBar(
    onBack: () -> Unit,
    onSubmit: () -> Unit,
) {
    TopBar(
        start = { BackButton(onBack) },
        end = { TextButton(onClick = onSubmit) { Text(text = LocalStrings.current.submitQuestion, maxLines = 1) } },
    )
}

/** Submit's: the back arrow, to the Account screen it was opened from. */
@Composable
fun SubmitTopBar(onBack: () -> Unit) {
    TopBar(start = { BackButton(onBack) })
}

@Composable
private fun TopBar(
    start: @Composable RowScope.() -> Unit = {},
    end: @Composable RowScope.() -> Unit = {},
) {
    val dimens = WyrThemeAccessors.dimens

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = dimens.topBarHeight)
                .padding(horizontal = dimens.spaceXs),
    ) {
        start()
        Spacer(Modifier.weight(1f))
        end()
    }
}

@Composable
private fun AccountButton(onClick: () -> Unit) {
    IconAction(WyrIcons.Account, LocalStrings.current.account, onClick)
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    IconAction(WyrIcons.Back, LocalStrings.current.back, onClick)
}

/** An icon button, named for a screen reader in the language shown, in the heading's accent. */
@Composable
private fun IconAction(
    icon: ImageVector,
    name: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = name, tint = WyrThemeAccessors.colors.headingAccent)
    }
}
