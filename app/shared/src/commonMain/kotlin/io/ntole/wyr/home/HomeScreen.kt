package io.ntole.wyr.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import io.ntole.wyr.analytics.tapped
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.navigation.HomeTopBar
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale

/**
 * The Home screen, the one the app opens on (CLAUDE.md §8d, *Navigation*): the game's name, a big
 * Play button, which [onPlay] answers, and the account icon top right, which [onAccount] answers.
 * Nothing else, the user asking for less text.
 */
@Composable
fun HomeScreen(
    onPlay: () -> Unit,
    onAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val strings = LocalStrings.current

    Surface(color = colors.pageBackground, modifier = modifier.fillMaxSize()) {
        Column {
            HomeTopBar(onAccount = onAccount)

            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(dimens.spaceXl),
                    modifier = Modifier.padding(dimens.screenPadding),
                ) {
                    Text(
                        text = strings.gameName,
                        color = colors.headingAccent,
                        fontSize = WyrTypeScale.gameName,
                        lineHeight = WyrTypeScale.gameNameLineHeight,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                    )
                    Button(
                        onClick = tapped("home.play", onClick = onPlay),
                        modifier = Modifier.width(dimens.playButtonWidth).heightIn(min = dimens.playButtonHeight),
                    ) {
                        Text(text = strings.play, fontSize = WyrTypeScale.playButton, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
