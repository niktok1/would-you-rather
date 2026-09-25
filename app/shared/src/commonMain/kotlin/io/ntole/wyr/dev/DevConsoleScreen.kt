package io.ntole.wyr.dev

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.session.SessionInfo
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.VoteOutcome
import io.ntole.wyr.core.network.trace.HttpExchange
import io.ntole.wyr.theme.WyrThemeAccessors
import io.ntole.wyr.theme.WyrTypeScale
import kotlin.time.Instant

/**
 * The engineering console: one plain scrolling page, a section per area, stock Material parts.
 *
 * Deliberately unpolished (CLAUDE.md §8d). Its job is to show exactly what the domain returned,
 * including the diagnostic messages a player must never see.
 *
 * [submitSection] is the *Submit a question* section, which keeps a ViewModel of its own
 * (`io.ntole.wyr.dev.submission`). Moderating is not the game's: it is the moderation app's
 * (`:app:adminApp`, CLAUDE.md §8d).
 */
@Composable
fun DevConsoleScreen(
    state: DevConsoleState,
    httpExchanges: List<HttpExchange>,
    onEnsureSession: () -> Unit,
    onNewGuest: () -> Unit,
    onNextQuestion: () -> Unit,
    onResetQueue: () -> Unit,
    onVote: (Side) -> Unit,
    onVoteById: (questionId: String, side: Side) -> Unit,
    onRetryLastVote: () -> Unit,
    onAnswerMany: (count: Int) -> Unit,
    onReadStats: () -> Unit,
    modifier: Modifier = Modifier,
    submitSection: @Composable () -> Unit = {},
) {
    val dimens = WyrThemeAccessors.dimens
    val idle = !state.isBusy

    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(dimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.spaceMd),
        ) {
            Header(state)

            Section("Play") {
                val question = state.question
                if (question == null) {
                    Value("question", "none loaded")
                } else {
                    Value("id", question.id)
                    Value("categories", namesOf(question.categories))
                    Value("answeredBefore", question.answeredBefore.toString())
                    Value("likeCount", question.likeCount.toString())
                    Value("likedByMe", question.likedByMe.toString())
                    Value("A", question.optionA)
                    Value("B", question.optionB)
                }
                Buttons {
                    Button(onClick = { onVote(Side.A) }, enabled = idle && question != null) { Text("A") }
                    Button(onClick = { onVote(Side.B) }, enabled = idle && question != null) { Text("B") }
                }
                val lastVote = state.lastVote
                Value(
                    "last vote",
                    lastVote?.let { "${it.questionId} ${it.side} attempt=${it.attempt.value}" } ?: "none",
                )
                Buttons {
                    OutlinedButton(onClick = onRetryLastVote, enabled = idle && lastVote != null) {
                        Text("Retry last vote (same attempt)")
                    }
                }
                state.lastOutcome?.let { Outcome(it) }
            }

            Section("Answer many") {
                var countText by rememberSaveable { mutableStateOf(ANSWER_MANY_DEFAULT) }
                val count = countText.trim().toIntOrNull()?.takeIf { it in 1..DevConsoleViewModel.MAX_ANSWER_MANY }
                OutlinedTextField(
                    value = countText,
                    onValueChange = { countText = it },
                    label = { Text("N, 1 to ${DevConsoleViewModel.MAX_ANSWER_MANY}") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Buttons {
                    Button(onClick = { count?.let(onAnswerMany) }, enabled = idle && count != null) {
                        Text("Answer N")
                    }
                }
            }

            Section("Stats") {
                Stats(state)
                Buttons {
                    OutlinedButton(onClick = onReadStats, enabled = idle) { Text("Read stats") }
                }
            }

            Section("Session") {
                Value("account", accountOf(state.stats))
                Buttons {
                    Button(onClick = onEnsureSession, enabled = idle) { Text("Ensure session") }
                    OutlinedButton(onClick = onNewGuest, enabled = idle) { Text("New guest") }
                }
            }

            Section("Questions") {
                Value("queue size", state.queueSize?.toString() ?: "unknown")
                Buttons {
                    Button(onClick = onNextQuestion, enabled = idle) { Text("Next question") }
                    OutlinedButton(onClick = onResetQueue, enabled = idle) { Text("Reset queue") }
                }
            }

            Section("Vote by id") {
                var questionId by rememberSaveable { mutableStateOf("") }
                OutlinedTextField(
                    value = questionId,
                    onValueChange = { questionId = it },
                    label = { Text("questionId") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Buttons {
                    Button(onClick = { onVoteById(questionId, Side.A) }, enabled = idle) { Text("Vote A") }
                    Button(onClick = { onVoteById(questionId, Side.B) }, enabled = idle) { Text("Vote B") }
                }
            }

            submitSection()

            Section("Action log") {
                if (state.log.isEmpty()) Value("log", "empty")
                state.log.forEach { entry -> LogLine(entry) }
            }

            Section("HTTP trace") {
                if (httpExchanges.isEmpty()) Value("trace", "empty")
                httpExchanges.forEach { exchange -> TraceLine(exchange) }
            }
        }
    }
}

@Composable
private fun Header(state: DevConsoleState) {
    Column(verticalArrangement = Arrangement.spacedBy(WyrThemeAccessors.dimens.spaceXs)) {
        Text("Dev console", style = MaterialTheme.typography.headlineSmall)
        // First, so which server this build talks to is never in doubt (CLAUDE.md §8e).
        Value("environment", state.environment.displayName)
        Value("api", state.environment.apiBaseUrl)
        Value("player", state.session?.playerId ?: "no session")
        Value("token expires", tokenExpiry(state.session))
        Value("total points", state.lastOutcome?.totalPoints?.toString() ?: "no vote yet")
        val running = state.running
        if (running != null) {
            Value("running", running)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * What the stats are read for here: whose they are and their total, and whether they disagree with
 * the last outcome, or are another player's and so say nothing about it. The rest of the numbers are
 * the game's Account tab's (CLAUDE.md §8d, *Stats*), and the log's.
 */
@Composable
private fun Stats(state: DevConsoleState) {
    val stats = state.stats
    if (stats == null) {
        Value("stats", "not read")
    } else {
        Value("playerId", stats.playerId)
        Value("totalPoints", stats.totalPoints.toString())
    }
    if (state.statsForAnotherPlayer) {
        Value("lastOutcome", "paid to ${state.lastOutcomePlayerId}, not compared")
    }
    if (state.likesMovedSinceOutcome) {
        Value("lastOutcome", likesMovedNote(state.likesReceivedAtOutcome, stats?.likesReceived))
    }
    if (state.pointsMismatch) {
        val outcomeTotal = state.lastOutcome?.totalPoints
        CodeLine("MISMATCH totalPoints: stats=${stats?.totalPoints} lastOutcome=$outcomeTotal", failed = true)
    }
}

/** The whole [VoteOutcome] as the domain holds it, derived values included. */
@Composable
private fun Outcome(outcome: VoteOutcome) {
    val tally = outcome.tally
    Value("outcome for", outcome.questionId)
    Value("your side", outcome.yourSide.name)
    Value("votes A/B", "${tally.votesA} / ${tally.votesB}")
    Value("percent A/B", "${tally.percentA} / ${tally.percentB}")
    Value("majority", tally.majority?.name ?: "tie")
    Value("agreedWithMajority", outcome.agreedWithMajority.toString())
    Value("pointsAwarded", outcome.pointsAwarded.toString())
    Value("totalPoints", outcome.totalPoints.toString())
    Value("replayed", outcome.replayed.toString())
}

@Composable
private fun LogLine(entry: LogEntry) {
    val (outcome, failed) =
        when (val result = entry.result) {
            is LogResult.Ok -> "ok ${result.summary}" to false
            is LogResult.Err -> "err ${result.error} ${result.message.orEmpty()}" to true
            is LogResult.Crash -> "crash ${result.type} ${result.message.orEmpty()}" to true
        }
    CodeLine("${entry.action}(${entry.args}) ${entry.elapsedMillis}ms -> $outcome", failed)
}

@Composable
private fun TraceLine(exchange: HttpExchange) {
    val (outcome, failed) =
        when (val result = exchange.outcome) {
            is HttpExchange.Outcome.Answered -> result.status.toString() to (result.status >= HTTP_ERROR_FROM)
            is HttpExchange.Outcome.Failed -> result.exceptionClass to true
        }
    CodeLine("${exchange.method} ${exchange.pathAndQuery} -> $outcome ${exchange.elapsedMillis}ms", failed)
}

@Composable
private fun CodeLine(
    text: String,
    failed: Boolean,
) {
    val color = if (failed) MaterialTheme.colorScheme.error else LocalContentColor.current
    Text(text = text, style = WyrTypeScale.code, color = color)
}

@Composable
private fun Value(
    label: String,
    value: String,
) {
    Text(text = "$label: $value", style = WyrTypeScale.code)
}

@Composable
private fun Section(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(WyrThemeAccessors.dimens.spaceSm)) {
        HorizontalDivider()
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    val dimens = WyrThemeAccessors.dimens
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm),
        verticalArrangement = Arrangement.spacedBy(dimens.spaceSm),
    ) {
        content()
    }
}

/**
 * Whose account the session is, by the last stats read: its username, or a guest's, who has none.
 * Registering and logging in are the game's Account tab (CLAUDE.md §8d, *Current focus*), not this.
 */
internal fun accountOf(stats: PlayerStats?): String = if (stats == null) "stats not read" else stats.username ?: "guest"

/**
 * Why the stats are not compared with the last outcome once the likes received have moved since:
 * [atOutcome] is what the first read after it counted, [now] what the stats count.
 */
internal fun likesMovedNote(
    atOutcome: Int?,
    now: Int?,
): String = "likesReceived $atOutcome then, $now now, not compared"

/** Every one of [categories], in the order the set holds them. */
internal fun namesOf(categories: Set<Category>): String = categories.joinToString(", ") { it.name }

private fun tokenExpiry(session: SessionInfo?): String {
    if (session == null) return "no session"
    val millis = session.accessTokenExpiresAtEpochMillis ?: return "unreadable token"
    return "${Instant.fromEpochMilliseconds(millis)} ($millis)"
}

private const val HTTP_ERROR_FROM = 400

private const val ANSWER_MANY_DEFAULT = "5"
