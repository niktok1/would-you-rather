package io.ntole.wyr.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import io.ntole.wyr.analytics.tapped
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

/**
 * Play's: home on the left, back to Home, the account icon on the right, and between them, in the
 * middle, [categories]: the categories played, which open the Categories screen (CLAUDE.md §8d,
 * *The Play screen*).
 */
@Composable
fun PlayTopBar(
    onHome: () -> Unit,
    onAccount: () -> Unit,
    categories: @Composable () -> Unit,
) {
    TopBar(
        start = { IconAction(WyrIcons.Home, LocalStrings.current.home, "top_bar.home", onHome) },
        middle = categories,
        end = { AccountButton(onAccount) },
    )
}

/**
 * The Auth page's, Submit's, the Categories screen's and the About screen's: the back arrow, to the
 * screen each was opened from. The Account screen's has the About screen's icon besides
 * ([AccountTopBar]); the way to the Submit screen is on the Account screen itself, in My questions
 * (§8d, *The Account screen*).
 */
@Composable
fun BackTopBar(onBack: () -> Unit) {
    TopBar(start = { BackButton(onBack) })
}

/**
 * The Account screen's: the back arrow, and on the right the info icon, to the About screen (CLAUDE.md
 * §8d, *About*), so the way there adds no text to the screen.
 */
@Composable
fun AccountTopBar(
    onBack: () -> Unit,
    onAbout: () -> Unit,
) {
    TopBar(
        start = { BackButton(onBack) },
        end = { IconAction(WyrIcons.Info, LocalStrings.current.aboutScreen.title, "top_bar.about", onAbout) },
    )
}

/**
 * [start] on the left, [end] on the right, and [middle], if any, in what they leave, in its middle: in
 * the middle of the bar too wherever [start] and [end] are as wide, as two icon buttons are.
 */
@Composable
private fun TopBar(
    start: @Composable RowScope.() -> Unit = {},
    middle: (@Composable () -> Unit)? = null,
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
        if (middle == null) {
            Spacer(Modifier.weight(1f))
        } else {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f)) { middle() }
        }
        end()
    }
}

@Composable
private fun AccountButton(onClick: () -> Unit) {
    IconAction(WyrIcons.Account, LocalStrings.current.account, "top_bar.account", onClick)
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    IconAction(WyrIcons.Back, LocalStrings.current.back, "top_bar.back", onClick)
}

/**
 * An icon button, named for a screen reader in the language shown, in the heading's accent, whose
 * taps the analytics count as [element]'s (CLAUDE.md §8g).
 */
@Composable
private fun IconAction(
    icon: ImageVector,
    name: String,
    element: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = tapped(element, onClick = onClick)) {
        Icon(imageVector = icon, contentDescription = name, tint = WyrThemeAccessors.colors.headingAccent)
    }
}
