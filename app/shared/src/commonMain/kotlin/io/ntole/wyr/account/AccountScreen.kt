package io.ntole.wyr.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.network.environment.WyrEnvironment
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.LanguageSwitch
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale

/**
 * The Account screen (CLAUDE.md §8d, *The Account screen*): the language switch first, [language]
 * the one the game is shown in, which [onSelectLanguage] changes (§8f); then who is playing on this
 * device and their stats, the points first; for a guest, one button to the Auth page, which
 * [onOpenAuth] opens, to register or log in; for a registered player, Log out. A build for any server
 * but production's names that server last ([serverLine]), [environment] being the one the build
 * talks to.
 *
 * Plain on purpose while UI polish is paused, and every colour, space and size from the theme (§5b).
 */
@Composable
fun AccountScreen(
    state: AccountState,
    actions: AccountActions,
    environment: WyrEnvironment,
    language: Language,
    onSelectLanguage: (Language) -> Unit,
    onOpenAuth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens

    Surface(color = colors.pageBackground, modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(dimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.spaceLg),
        ) {
            LanguageSwitch(selected = language, onSelect = onSelectLanguage)

            Status(state, actions)

            val stats = state.stats
            when {
                stats == null -> {}

                stats.username == null -> {
                    Button(onClick = onOpenAuth, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                        Text(LocalStrings.current.accountScreens.openAuth)
                    }
                }

                else -> {
                    LogOutSection(state, actions)
                }
            }

            serverLine(environment)?.let { line ->
                Text(text = line, color = colors.muted, fontSize = WyrTypeScale.statLabel)
            }
        }
    }
}

@Composable
private fun Status(
    state: AccountState,
    actions: AccountActions,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens

    Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceSm)) {
        val stats = state.stats
        val failure = state.failure?.takeIf { it.action == AccountAction.LOAD }
        when {
            stats != null -> {
                Text(text = playingAs(stats), color = colors.primaryText, fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceXs)) {
                    statLines(stats).forEach { line ->
                        Text(text = line, color = colors.muted, fontSize = WyrTypeScale.statLabel)
                    }
                }
            }

            failure == null -> {
                CircularProgressIndicator(color = colors.headingAccent)
            }
        }
        if (failure != null) {
            FailureText(failure)
            OutlinedButton(onClick = actions::refresh, enabled = !state.isBusy) {
                Text(LocalStrings.current.accountScreens.tryAgain)
            }
        }
        if (state.isBusy && stats != null) {
            LinearProgressIndicator(color = colors.headingAccent, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun LogOutSection(
    state: AccountState,
    actions: AccountActions,
) {
    Section(
        title = "Log out",
        note = "This device goes back to a new guest. Log in again any time with your username and password.",
    ) {
        FailureOf(state, AccountAction.LOG_OUT)
        OutlinedButton(onClick = actions::logOut, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
            Text("Log out")
        }
    }
}

@Composable
private fun Section(
    title: String,
    note: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = WyrThemeAccessors.colors

    Column(verticalArrangement = Arrangement.spacedBy(WyrThemeAccessors.dimens.spaceSm)) {
        Text(
            text = title,
            color = colors.primaryText,
            fontSize = WyrTypeScale.sectionTitle,
            fontWeight = FontWeight.Bold,
        )
        Text(text = note, color = colors.muted, fontSize = WyrTypeScale.statLabel)
        content()
    }
}

/** Who is playing on this device. */
internal fun playingAs(stats: PlayerStats): String = stats.username?.let { "Logged in as $it" } ?: "Playing as guest"

internal fun pointsOf(stats: PlayerStats): String = pointsText(stats.totalPoints)

/**
 * The player's stats, a line each, as the server counted them (CLAUDE.md §8d, *Stats*): the points,
 * the answers given and the questions they went to (a re-answer is one more answer to the same
 * question), the cycle and how many questions are left in it, neither answered nor skipped, and the
 * likes the questions the player submitted hold. Nothing is worked out here.
 */
internal fun statLines(stats: PlayerStats): List<String> =
    listOf(
        pointsOf(stats),
        "${counted(stats.answersGiven, "answer")} to ${counted(stats.questionsAnswered, "question")}",
        "Cycle ${stats.cycle}: ${counted(stats.dueThisCycle, "question")} left",
        "${counted(stats.likesReceived, "like")} on questions you submitted",
    )

/**
 * The server a LOCAL or DEV build talks to, by name and URL, so a tester can tell which one they are
 * on (CLAUDE.md §8e), or null in a PROD build, whose players have no other server to tell it from.
 */
internal fun serverLine(environment: WyrEnvironment): String? =
    if (environment == WyrEnvironment.PROD) null else "Server: ${environment.displayName} (${environment.apiBaseUrl})"

private fun pointsText(points: Int): String = counted(points, "point")

/** [count] of [noun], which takes an s but for one. */
private fun counted(
    count: Int,
    noun: String,
): String = if (count == 1) "1 $noun" else "$count ${noun}s"
