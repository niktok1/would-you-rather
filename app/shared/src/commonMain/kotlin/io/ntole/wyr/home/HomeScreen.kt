package io.ntole.wyr.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import io.ntole.wyr.analytics.tapped
import io.ntole.wyr.core.domain.analytics.AnalyticsProperty
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.navigation.HomeTopBar
import io.ntole.wyr.play.CountedUpText
import io.ntole.wyr.play.QuestionLayout
import io.ntole.wyr.play.percentStyle
import io.ntole.wyr.play.rememberCountUp
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale

/**
 * The Home screen, the one the app opens on (CLAUDE.md §8d, *Navigation*, *Home picks*): the game's
 * name and, under it, two big Play buttons in the answer cards' colours (§5b), as the Play screen
 * stands its cards, stacked or side by side by the room ([QuestionLayout]), so Home looks like the
 * game. Each starts the game alike, [onPlay] told which was tapped, and shows its share of every
 * player's taps, [picks], counted up from 0 as a reveal's percentage is ([CountedUpText]): none until
 * they are read, and none while nobody has tapped. The account icon, top right, is [onAccount], with
 * a dot while [news] waits there. Nothing else, the user asking for less text.
 */
@Composable
fun HomeScreen(
    picks: Tally?,
    onPlay: (Side) -> Unit,
    onAccount: () -> Unit,
    modifier: Modifier = Modifier,
    news: Boolean = false,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val strings = LocalStrings.current
    val shares = picks?.takeIf { it.hasVotes }

    Surface(color = colors.pageBackground, modifier = modifier.fillMaxSize()) {
        Column {
            HomeTopBar(onAccount = onAccount, news = news)

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(dimens.spaceLg),
                modifier = Modifier.fillMaxWidth().weight(1f).padding(dimens.screenPadding),
            ) {
                Text(
                    text = strings.gameName,
                    color = colors.headingAccent,
                    fontSize = WyrTypeScale.gameName,
                    lineHeight = WyrTypeScale.gameNameLineHeight,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                )
                QuestionLayout(
                    wideMinWidth = dimens.wideLayoutMinWidth,
                    gap = dimens.spaceMd,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    PlayButton(Side.A, share = shares?.percentA, onPlay = onPlay)
                    // Where the Play screen has its row: the gap between the buttons stacked, under them side by side.
                    Spacer(Modifier.height(dimens.spaceMd))
                    PlayButton(Side.B, share = shares?.percentB, onPlay = onPlay)
                }
            }
        }
    }
}

/**
 * One of the two Play buttons, in [side]'s card colour, *Play* and, once read, [share], counted up from
 * 0 as the reveal counts. Its tap is `home.play` to the analytics, with its side (CLAUDE.md §8g).
 */
@Composable
private fun PlayButton(
    side: Side,
    share: Int?,
    onPlay: (Side) -> Unit,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val strings = LocalStrings.current

    Surface(
        onClick = tapped("home.play", mapOf(AnalyticsProperty.SIDE to side.name)) { onPlay(side) },
        shape = RoundedCornerShape(dimens.radiusCard),
        color = if (side == Side.A) colors.optionA else colors.optionB,
        contentColor = if (side == Side.A) colors.onOptionA else colors.onOptionB,
        modifier = Modifier.fillMaxWidth().heightIn(min = dimens.playButtonHeight),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(dimens.spaceMd),
            ) {
                Text(text = strings.play, fontSize = WyrTypeScale.playButton, fontWeight = FontWeight.Bold)
                if (share != null) {
                    Spacer(Modifier.size(dimens.spaceSm))
                    CountedUpText(
                        counted = rememberCountUp(share),
                        target = share,
                        text = strings.playScreen::percent,
                        style = percentStyle(),
                    )
                }
            }
        }
    }
}
