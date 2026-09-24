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
import androidx.compose.material3.FilterChip
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
    onSkip: () -> Unit,
    onVoteById: (questionId: String, side: Side) -> Unit,
    onRetryLastVote: () -> Unit,
    onAnswerMany: (count: Int) -> Unit,
    onReadStats: () -> Unit,
    onToggleCategory: (Category) -> Unit,
    onSelectAllCategories: () -> Unit,
    modifier: Modifier = Modifier,
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
                    Value("A", question.optionA)
                    Value("B", question.optionB)
                }
                Buttons {
                    Button(onClick = { onVote(Side.A) }, enabled = idle && question != null) { Text("A") }
                    Button(onClick = { onVote(Side.B) }, enabled = idle && question != null) { Text("B") }
                    OutlinedButton(onClick = onSkip, enabled = idle && question != null) { Text("Skip") }
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

            Section("Category") {
                Value("feed filtered to", feedFilterOf(state.categories))
                Buttons {
                    // None selected is every category, so All shows as selected then.
                    FilterChip(
                        selected = state.categories.isEmpty(),
                        onClick = onSelectAllCategories,
                        label = { Text("All") },
                        enabled = idle,
                    )
                    Category.selectable.forEach { category ->
                        FilterChip(
                            selected = category in state.categories,
                            onClick = { onToggleCategory(category) },
                            label = { Text(category.name) },
                            enabled = idle,
                        )
                    }
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
        Value("api", state.apiBaseUrl)
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
 * The stats as the server counted them, and whether they disagree with the last outcome, or are
 * another player's and so say nothing about it.
 */
@Composable
private fun Stats(state: DevConsoleState) {
    val stats = state.stats
    if (stats == null) {
        Value("stats", "not read")
    } else {
        Value("playerId", stats.playerId)
        Value("totalPoints", stats.totalPoints.toString())
        Value("answersGiven", stats.answersGiven.toString())
        Value("questionsAnswered", stats.questionsAnswered.toString())
        Value("cycle", stats.cycle.toString())
        Value("dueThisCycle", stats.dueThisCycle.toString())
    }
    if (state.statsForAnotherPlayer) {
        Value("lastOutcome", "paid to ${state.lastOutcomePlayerId}, not compared")
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

/** Every one of [categories], in the order the set holds them. */
internal fun namesOf(categories: Set<Category>): String = categories.joinToString(", ") { it.name }

/** What a feed filtered to [categories] serves: every category when there are none. */
internal fun feedFilterOf(categories: Set<Category>): String =
    if (categories.isEmpty()) "every category" else namesOf(categories)

private fun tokenExpiry(session: SessionInfo?): String {
    if (session == null) return "no session"
    val millis = session.accessTokenExpiresAtEpochMillis ?: return "unreadable token"
    return "${Instant.fromEpochMilliseconds(millis)} ($millis)"
}

private const val HTTP_ERROR_FROM = 400

private const val ANSWER_MANY_DEFAULT = "5"
