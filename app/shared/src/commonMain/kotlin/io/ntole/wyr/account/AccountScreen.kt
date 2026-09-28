package io.ntole.wyr.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.ntole.wyr.analytics.tapped
import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.network.environment.WyrEnvironment
import io.ntole.wyr.language.AccountStrings
import io.ntole.wyr.language.GOOGLE_PLAY
import io.ntole.wyr.language.LocalStrings
import io.ntole.wyr.language.PlayGamesStrings
import io.ntole.wyr.language.Strings
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
 * [onOpenAuth] opens, to register or log in; then My questions, a table of them, whose plus
 * [onNewQuestion] answers with the Submit screen's form, for a registered player, and whose rows
 * [onOpenQuestion] opens, each by its id, on a screen of its details; then, quiet and together, the
 * Statistics switch, [statisticsOn] whether the player lets the game send analytics, which
 * [onStatisticsChange] changes (§8g), with an info icon that says what it sends, and under it Log
 * out, for a player with a username. A build for any server but production's names that server last
 * ([serverLine]), [environment] being the one the build talks to. The questions in [newDecisions] a
 * moderator decided since the player last saw them, and My questions marks each (CLAUDE.md §8d,
 * *Submitting*).
 *
 * The language menu is not here for now (CLAUDE.md §8f), and deleting the account is on the About
 * screen ([DeleteAccount]).
 *
 * Plain on purpose, and short, the user asking for less text: every colour, space and size from the
 * theme (§5b), every word from [LocalStrings] (§8f).
 */
@Composable
fun AccountScreen(
    state: AccountState,
    actions: AccountActions,
    environment: WyrEnvironment,
    statisticsOn: Boolean,
    onStatisticsChange: (Boolean) -> Unit,
    onOpenAuth: () -> Unit,
    onNewQuestion: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenQuestion: (String) -> Unit = {},
    newDecisions: Set<String> = emptySet(),
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
            if (stats != null) MyQuestions(state, actions, onNewQuestion, onOpenQuestion, newDecisions)

            Settings(state, actions, statisticsOn, onStatisticsChange)

            serverLine(environment, strings)?.let { line ->
                Text(text = line, color = colors.muted, fontSize = WyrTypeScale.statLabel)
            }
        }
    }
}

/**
 * The options, quiet and together, the user asking for them dimmed: the Statistics switch, and under
 * it Log out for a player with a username, once one is read, and why it failed. A player registered by
 * Play Games alone has no Log out: it would only make the device a fresh guest, and the Auth page's
 * Play Games button the one way back to their account.
 */
@Composable
private fun Settings(
    state: AccountState,
    actions: AccountActions,
    statisticsOn: Boolean,
    onStatisticsChange: (Boolean) -> Unit,
) {
    val colors = WyrThemeAccessors.colors
    val dimens = WyrThemeAccessors.dimens

    Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceXs)) {
        HorizontalDivider(color = colors.orPillBackground)
        StatisticsSwitch(on = statisticsOn, onChange = onStatisticsChange)
        FailureOf(state, AccountAction.LOG_OUT)
        if (state.stats?.username != null) {
            TextButton(
                onClick = tapped("account.log_out", onClick = actions::logOut),
                enabled = !state.isBusy,
                contentPadding = PaddingValues(),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.muted),
            ) {
                Text(LocalStrings.current.accountScreens.logOut)
            }
        }
    }
}

/**
 * The Statistics switch (CLAUDE.md §8g), dimmed: whether the player lets the game send analytics,
 * [on], which a tap anywhere on it turns the other way, [onChange]. Its word and the switch are one
 * control, which a screen reader hears as the word, a switch, and on or off; the info icon beside the
 * word is a button of its own, which opens a dialog saying what is sent and what never is.
 */
@Composable
private fun StatisticsSwitch(
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

/**
 * Who is playing, their points and their stats, on a card, and a guest's one button to the Auth page;
 * before the
 * first read works, a spinner, or why it failed with Try again; and while an action runs, a bar under
 * the card, unless a failure shows.
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
            PlayerCard(stats, state.playGamesName, busy = state.isBusy, onOpenAuth = onOpenAuth)
        } else if (failure == null) {
            LoadingSpinner()
        }
        // Not while a failure shows: the action it names is over, and the read after it only keeps the
        // buttons off a moment longer, so the bar's row goes to the failure and the screen still fits.
        if (state.isBusy && stats != null && state.failure == null) {
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
 * row, so more fit as they come; and for a guest the one button to the Auth page. A player registered
 * by Play Games alone, named for it, gets no way there: a username is only for logging in where there
 * is no Play Games, on iOS and the web, neither launched yet.
 */
@Composable
private fun PlayerCard(
    stats: PlayerStats,
    playGamesName: String?,
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
                Avatar(stats.username ?: playGamesName.takeIf { stats.playGamesLinked })
                Text(
                    text = nameOf(stats, LocalStrings.current, playGamesName),
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
            val cells = statCells(stats, strings).map { cell -> statSlot(cell) }
            cells.chunked(STATS_PER_ROW).forEach { row ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm),
                ) {
                    row.forEach { cell -> cell(Modifier.weight(1f)) }
                    repeat(STATS_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (!stats.registered) {
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
 * The player's initial, the first letter of their name in capitals, in a circle, or a guest's
 * figure for a player with no name. Nothing for a screen reader: the name beside it says it.
 */
@Composable
private fun Avatar(name: String?) {
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
        val initial = name?.firstOrNull()?.uppercase()
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

/** [cell] as one of the card's grid cells, laid out in the modifier its row gives it. */
private fun statSlot(cell: StatCell): @Composable (Modifier) -> Unit = { modifier -> Stat(cell, modifier) }

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

/**
 * Who is playing on this device: their username, or for a player registered by Play Games alone the
 * name they go by there, [playGamesName], or the service's ([PlayGamesStrings.name]) when this
 * device's Play Games gave none, or [AccountStrings.guest] for a guest.
 */
internal fun nameOf(
    stats: PlayerStats,
    strings: Strings,
    playGamesName: String? = null,
): String =
    stats.username
        ?: if (stats.playGamesLinked) {
            playGamesName ?: strings.playGames.name.fill(GOOGLE_PLAY)
        } else {
            strings.accountScreens.guest
        }

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
