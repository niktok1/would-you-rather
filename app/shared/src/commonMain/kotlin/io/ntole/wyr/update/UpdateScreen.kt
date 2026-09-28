package io.ntole.wyr.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import io.ntole.wyr.analytics.tapped
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.theme.PageSurface
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale

/**
 * The one screen the game shows once the server serves this build nothing more (CLAUDE.md §8e, *The
 * build on every request*): that a new version is available, and [button], this platform's way to it,
 * where it has one. Nothing else, no top bar and no way back: every call would be refused again.
 */
@Composable
fun UpdateScreen(
    button: UpdateButton?,
    modifier: Modifier = Modifier,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val strings = LocalStrings.current.updateScreen

    PageSurface(modifier = modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(dimens.screenPadding)) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(dimens.spaceXl),
            ) {
                Text(
                    text = strings.newVersion,
                    color = colors.headingAccent,
                    fontSize = WyrTypeScale.heading,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                )
                if (button != null) {
                    val (label, element) =
                        when (button.way) {
                            UpdateWay.STORE -> strings.update to "update.store"
                            UpdateWay.RELOAD -> strings.reload to "update.reload"
                        }
                    Button(onClick = tapped(element, onClick = button.go)) {
                        Text(text = label, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
