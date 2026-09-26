package io.ntole.wyr.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.ntole.wyr.analytics.tapped
import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.network.environment.WyrEnvironment
import io.ntole.wyr.language.AccountStrings
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.LanguageMenu
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.language.fill
import io.ntole.wyr.loading.LoadingSpinner
import io.ntole.wyr.points.PointsAmount
import io.ntole.wyr.theme.WyrIcons
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale
import io.ntole.wyr.theme.contentWidth

/**
 * The Account screen (CLAUDE.md §8d, *The Account screen*), in the user's order: who is playing on
 * this device, their points and their stats, and for a guest one button to the Auth page, which
 * [onOpenAuth] opens, to register or log in; then My questions, a table of them, whose New question
 * [onNewQuestion] answers with the Submit screen's form, for a registered player; then the language
 * menu, [language] the one the game is shown in, which [onSelectLanguage] changes (§8f), and beside
 * it the Statistics switch, [statisticsOn] whether the player lets the game send analytics, which
 * [onStatisticsChange] changes (§8g); under them Log out for a registered player; and at the bottom a
 * quiet Delete account, for anyone read, which asks first (§8a, *Deleting an account*).
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
    statisticsOn: Boolean,
    onStatisticsChange: (Boolean) -> Unit,
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
                    .padding(dimens.screenPadding)
                    .contentWidth(dimens.contentMaxWidth),
            verticalArrangement = Arrangement.spacedBy(dimens.spaceMd),
        ) {
            Player(state, actions, onOpenAuth)

            // The player's own questions, once there is a player to read them for.
            if (stats != null) MyQuestions(state, actions, onNewQuestion)

            // The language menu and beside it the Statistics switch, one row of the two, and under them
            // Log out for a registered player (provisional, CLAUDE.md §8b: Log out was beside the menu),
            // with a quiet Delete account at the start of its row, for a guest too (provisional, §8b).
            Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceSm)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm),
                ) {
                    LanguageMenu(selected = language, onSelect = onSelectLanguage, modifier = Modifier.weight(1f))
                    StatisticsSwitch(on = statisticsOn, onChange = onStatisticsChange)
                }
                if (stats != null) LogOutAndDelete(state, actions, registered = stats.username != null)
            }

            serverLine(environment, strings)?.let { line ->
                Text(text = line, color = colors.muted, fontSize = WyrTypeScale.statLabel)
            }
        }
    }
}

/**
 * The last row, once a player is read: a quiet Delete account at its start, for a guest and a
 * [registered] player alike, and a registered player's Log out at its end; why either failed, above
 * it. Delete account is not offered while the player could not be read again: the screen shows that
 * failure then, and the deletion, which needs the server as the read did, would only fail too.
 */
@Composable
private fun LogOutAndDelete(
    state: AccountState,
    actions: AccountActions,
    registered: Boolean,
) {
    val offerDeletion = state.failure?.action != AccountAction.LOAD
    FailureOf(state, AccountAction.LOG_OUT)
    FailureOf(state, AccountAction.DELETE)
    if (!registered && !offerDeletion) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (offerDeletion) DeleteAccount(state, actions)
        Spacer(Modifier.weight(1f))
        if (registered) {
            OutlinedButton(
                onClick = tapped("account.log_out", onClick = actions::logOut),
                enabled = !state.isBusy,
            ) {
                Text(LocalStrings.current.accountScreens.logOut)
            }
        }
    }
}

/**
 * Deleting the account (CLAUDE.md §8d, *The Account screen*): a quiet button, which asks in a dialog of
 * one line whether everything is to go for good, and only then deletes.
 */
@Composable
private fun DeleteAccount(
    state: AccountState,
    actions: AccountActions,
) {
    val colors = WyrThemeAccessors.colors
    val strings = LocalStrings.current.accountScreens.deleteAccount
    var confirming by rememberSaveable { mutableStateOf(false) }

    TextButton(
        onClick = tapped("account.delete") { confirming = true },
        enabled = !state.isBusy,
        colors = ButtonDefaults.textButtonColors(contentColor = colors.muted),
    ) {
        Text(strings.button)
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            text = { Text(strings.warning) },
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

/**
 * The Statistics switch (CLAUDE.md §8g): whether the player lets the game send analytics, [on], which
 * a tap anywhere on it turns the other way, [onChange]. Its word and the switch are one control, which
 * a screen reader hears as the word, a switch, and on or off.
 */
@Composable
private fun StatisticsSwitch(
    on: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val toggle = tapped("account.statistics") { onChange(!on) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm),
        modifier =
            Modifier
                .toggleable(value = on, role = Role.Switch, onValueChange = { toggle() })
                .minimumInteractiveComponentSize(),
    ) {
        Text(text = LocalStrings.current.accountScreens.statistics, color = colors.primaryText, maxLines = 1)
        // No click of its own: the row's toggleable is the one.
        Switch(checked = on, onCheckedChange = null)
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
    val stats = state.stats
    val failure = state.failure?.takeIf { it.action == AccountAction.LOAD }

    Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceSm)) {
        if (stats != null) {
            PlayerCard(stats, busy = state.isBusy, onOpenAuth = onOpenAuth)
        } else if (failure == null) {
            LoadingSpinner()
        }
        if (state.isBusy && stats != null) {
            LinearProgressIndicator(color = colors.headingAccent, modifier = Modifier.fillMaxWidth())
        }
        if (failure != null) {
            val tryAgain = @Composable {
                OutlinedButton(
                    onClick = tapped("account.try_again", onClick = actions::refresh),
                    enabled = !state.isBusy,
                ) { Text(LocalStrings.current.tryAgain) }
            }
            if (stats == null) {
                FailureText(failure)
                tryAgain()
            } else {
                // Under the card, what failed and Try again share a row, so the screen still fits.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm),
                ) {
                    Box(modifier = Modifier.weight(1f)) { FailureText(failure) }
                    tryAgain()
                }
            }
        }
    }
}

/**
 * The card (CLAUDE.md §8d, *The Account screen*): the player's initial in a circle, a guest's figure
 * for a guest, their name and their points, a coin and the number; under a line, their stats, two to a
 * row, so more fit as they come; and for a guest the one button to the Auth page.
 */
@Composable
private fun PlayerCard(
    stats: PlayerStats,
    busy: Boolean,
    onOpenAuth: () -> Unit,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens
    val strings = LocalStrings.current.accountScreens

    Surface(
        color = colors.surface,
        shape = RoundedCornerShape(dimens.radiusCard),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(dimens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(dimens.spaceSm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm),
            ) {
                Avatar(stats.username)
                Text(
                    text = nameOf(stats, strings),
                    color = colors.primaryText,
                    fontSize = WyrTypeScale.sectionTitle,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                PointsAmount(
                    points = stats.totalPoints,
                    fontSize = WyrTypeScale.heading,
                    fontWeight = FontWeight.ExtraBold,
                    color = colors.headingAccent,
                )
            }
            HorizontalDivider(color = colors.orPillBackground)
            statCells(stats, strings).chunked(STATS_PER_ROW).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm)) {
                    row.forEach { cell -> Stat(cell, Modifier.weight(1f)) }
                    repeat(STATS_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (stats.username == null) {
                Button(
                    onClick = tapped("account.open_auth", onClick = onOpenAuth),
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(strings.openAuth)
                }
            }
        }
    }
}

/**
 * The player's initial, the first letter of their username in capitals, in a circle, or a guest's
 * figure for a guest, who has no name. Nothing for a screen reader: the name beside it says it.
 */
@Composable
private fun Avatar(username: String?) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(dimens.avatarSize)
                .background(colors.orPillBackground, CircleShape)
                .clearAndSetSemantics {},
    ) {
        val initial = username?.firstOrNull()?.uppercase()
        if (initial != null) {
            Text(
                text = initial,
                color = colors.orPillText,
                fontSize = WyrTypeScale.sectionTitle,
                fontWeight = FontWeight.Bold,
            )
        } else {
            Icon(imageVector = WyrIcons.Account, contentDescription = null, tint = colors.orPillText)
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
    }
}

/** One of the player's stats as the card shows it: its number, and what it counts. */
internal data class StatCell(
    val value: String,
    val label: String,
)

/** Who is playing on this device: their username, or [AccountStrings.guest] for a guest. */
internal fun nameOf(
    stats: PlayerStats,
    strings: AccountStrings,
): String = stats.username ?: strings.guest

/**
 * The player's stats on the card, a number each, as the server counted them (CLAUDE.md §8d,
 * *Stats*): for now the distinct questions they have answered, however often each. What their own
 * questions hold, the likes and the answers, is in My questions' table, question by question and
 * added up. Nothing is worked out here.
 */
internal fun statCells(
    stats: PlayerStats,
    strings: AccountStrings,
): List<StatCell> = listOf(StatCell(stats.questionsAnswered.toString(), strings.questionsAnswered))

/** How many stats the card sets side by side in a row. */
private const val STATS_PER_ROW = 2

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
