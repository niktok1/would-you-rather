package io.ntole.wyr.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.network.environment.WyrEnvironment
import io.ntole.wyr.language.AccountStrings
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.LanguageSwitch
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.language.fill
import io.ntole.wyr.language.pointsText
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale

/**
 * The Account screen (CLAUDE.md §8d, *The Account screen*), in the user's order: who is playing on
 * this device, their points and their stats in a few numbers, and for a guest one button to the Auth
 * page, which [onOpenAuth] opens, to register or log in; then My questions, whose New question
 * [onNewQuestion] answers with the Submit screen's form; then the language switch, [language] the one
 * the game is shown in, which [onSelectLanguage] changes (§8f); then Log out for a registered player.
 * A build for any server but production's names that server last ([serverLine]), [environment] being
 * the one the build talks to.
 *
 * Plain on purpose, and short, the user asking for less text: every colour, space and size from the
 * theme (§5b), every word from [LocalStrings] (§8f).
 */
@Composable
fun AccountScreen(
    state: AccountState,
    actions: AccountActions,
    environment: WyrEnvironment,
    language: Language,
    onSelectLanguage: (Language) -> Unit,
    onOpenAuth: () -> Unit,
    onNewQuestion: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val strings = LocalStrings.current.accountScreens
    val stats = state.stats

    Surface(color = colors.pageBackground, modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(dimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.spaceMd),
        ) {
            Player(state, actions, onOpenAuth)

            // The player's own questions, once there is a player to read them for.
            if (stats != null) MyQuestions(state, actions, onNewQuestion)

            LanguageSwitch(selected = language, onSelect = onSelectLanguage)

            if (stats?.username != null) {
                Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceSm)) {
                    FailureOf(state, AccountAction.LOG_OUT)
                    OutlinedButton(
                        onClick = actions::logOut,
                        enabled = !state.isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(strings.logOut)
                    }
                }
            }

            serverLine(environment, strings)?.let { line ->
                Text(text = line, color = colors.muted, fontSize = WyrTypeScale.statLabel)
            }
        }
    }
}

/**
 * Who is playing, their points and their stats, on a card, and a guest's one button to the Auth page;
 * before the first read works, a spinner, or why it failed with Try again.
 */
@Composable
private fun Player(
    state: AccountState,
    actions: AccountActions,
    onOpenAuth: () -> Unit,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val strings = LocalStrings.current.accountScreens
    val stats = state.stats
    val failure = state.failure?.takeIf { it.action == AccountAction.LOAD }

    Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceSm)) {
        if (stats != null) {
            Surface(
                color = colors.surface,
                shape = RoundedCornerShape(dimens.radiusCard),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(dimens.spaceMd),
                    verticalArrangement = Arrangement.spacedBy(dimens.spaceSm),
                ) {
                    Text(
                        text = nameOf(stats, strings),
                        color = colors.primaryText,
                        fontSize = WyrTypeScale.sectionTitle,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = pointsText(stats.totalPoints),
                        color = colors.headingAccent,
                        fontSize = WyrTypeScale.heading,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm)) {
                        statCells(stats, strings).forEach { cell -> Stat(cell, Modifier.weight(1f)) }
                    }
                    if (stats.username == null) {
                        Button(onClick = onOpenAuth, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                            Text(strings.openAuth)
                        }
                    }
                }
            }
        } else if (failure == null) {
            CircularProgressIndicator(color = colors.headingAccent)
        }
        if (state.isBusy && stats != null) {
            LinearProgressIndicator(color = colors.headingAccent, modifier = Modifier.fillMaxWidth())
        }
        if (failure != null) {
            FailureText(failure)
            OutlinedButton(onClick = actions::refresh, enabled = !state.isBusy) { Text(strings.tryAgain) }
        }
    }
}

/** One stat, its number over what it counts, which a screen reader reads as one. */
@Composable
private fun Stat(
    cell: StatCell,
    modifier: Modifier,
) {
    val colors = WyrThemeAccessors.colors

    Column(modifier = modifier.semantics(mergeDescendants = true) {}) {
        Text(
            text = cell.value,
            color = colors.primaryText,
            fontSize = WyrTypeScale.sectionTitle,
            fontWeight = FontWeight.Bold,
        )
        Text(text = cell.label, color = colors.muted, fontSize = WyrTypeScale.statLabel)
        cell.note?.let { Text(text = it, color = colors.muted, fontSize = WyrTypeScale.statLabel) }
    }
}

/** One of the player's stats as the screen shows it: its number, what it counts, and what else it says. */
internal data class StatCell(
    val value: String,
    val label: String,
    val note: String? = null,
)

/** Who is playing on this device: their username, or [AccountStrings.guest] for a guest. */
internal fun nameOf(
    stats: PlayerStats,
    strings: AccountStrings,
): String = stats.username ?: strings.guest

/**
 * The player's stats, a number each, as the server counted them (CLAUDE.md §8d, *Stats*): the
 * answers given and the questions they went to (a re-answer is one more answer to the same question),
 * the cycle with the questions still due in it, neither answered nor skipped, and the likes the
 * questions the player submitted hold. Nothing is worked out here.
 */
internal fun statCells(
    stats: PlayerStats,
    strings: AccountStrings,
): List<StatCell> =
    listOf(
        StatCell(stats.answersGiven.toString(), strings.answers),
        StatCell(stats.questionsAnswered.toString(), strings.questions),
        StatCell(stats.cycle.toString(), strings.cycle, strings.cycleLeft.fill(stats.dueThisCycle)),
        StatCell(stats.likesReceived.toString(), strings.likes),
    )

/**
 * The server a LOCAL or DEV build talks to, by name and URL, so a tester can tell which one they are
 * on (CLAUDE.md §8e), or null in a PROD build, whose players have no other server to tell it from.
 */
internal fun serverLine(
    environment: WyrEnvironment,
    strings: AccountStrings,
): String? =
    if (environment == WyrEnvironment.PROD) {
        null
    } else {
        strings.serverLine.fill(environment.displayName, environment.apiBaseUrl)
    }
