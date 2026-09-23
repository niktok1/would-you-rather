package io.ntole.wyr.dev

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.question.GetNextQuestion
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.question.QuestionCache
import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.domain.session.SessionDiagnostics
import io.ntole.wyr.core.domain.session.SessionRepository
import io.ntole.wyr.core.domain.vote.CastVote
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.network.trace.HttpExchange
import io.ntole.wyr.core.network.trace.HttpTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * Drives the engineering console: every use case behind a button, and every result in the log.
 *
 * Everything goes through the same domain ports and use cases the game uses, so the console
 * exercises what ships rather than a parallel path to the server.
 */
class DevConsoleViewModel(
    apiBaseUrl: String,
    private val sessions: SessionRepository,
    private val diagnostics: SessionDiagnostics,
    private val questions: QuestionRepository,
    private val queue: QuestionCache,
    private val getNextQuestion: GetNextQuestion,
    private val castVote: CastVote,
    httpTrace: HttpTrace,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : ViewModel() {
    private val _state = MutableStateFlow(DevConsoleState(apiBaseUrl = apiBaseUrl))
    val state: StateFlow<DevConsoleState> = _state.asStateFlow()

    /** Plain data from the network layer, never a DTO, so it is passed through untouched. */
    val httpExchanges: StateFlow<List<HttpExchange>> = httpTrace.exchanges

    init {
        viewModelScope.launch { refreshSnapshot() }
    }

    fun ensureSession() = perform("ensureSession") { "playerId=${sessions.ensure()}" }

    /**
     * A fresh player from nothing. The queue is reset between dropping the session and minting
     * the next one, so the new player never resumes the old one's questions or cursor.
     */
    fun newGuest() =
        perform("newGuest") {
            sessions.clear()
            _state.update { it.copy(question = null, lastOutcome = null) }
            questions.reset()
            val playerId = sessions.ensure()
            "playerId=$playerId question=${loadQuestion().id}"
        }

    fun nextQuestion() = perform("nextQuestion") { "question=${loadQuestion().id}" }

    /** Skipping sends nothing (CLAUDE.md §8d), so it is only a fetch of the next question. */
    fun skip() =
        perform("skip", args = "questionId=${_state.value.question?.id}") {
            "question=${loadQuestion().id}"
        }

    fun resetQueue() =
        perform("resetQueue") {
            questions.reset()
            "queue emptied"
        }

    fun vote(side: Side) {
        val question = _state.value.question ?: return
        castAndShow("vote", question.id, side)
    }

    /** Any id at all, so the 404 and 409 paths can be provoked on purpose. */
    fun voteById(
        questionId: String,
        side: Side,
    ) = castAndShow("voteById", questionId.trim(), side)

    private fun castAndShow(
        action: String,
        questionId: String,
        side: Side,
    ) = perform(action, args = "questionId=$questionId side=$side") {
        val outcome = castVote(questionId, side)
        _state.update { it.copy(lastOutcome = outcome) }
        "+${outcome.pointsAwarded} total=${outcome.totalPoints}"
    }

    private suspend fun loadQuestion(): Question {
        val question = getNextQuestion()
        _state.update { it.copy(question = question) }
        return question
    }

    /**
     * Runs [block] as the one action in flight and logs how it ended. A second action while one
     * runs is ignored, so the log reads in the order things happened.
     */
    private fun perform(
        action: String,
        args: String = "",
        block: suspend () -> String,
    ) {
        if (_state.value.isBusy) return
        _state.update { it.copy(running = action) }

        viewModelScope.launch {
            try {
                val started = timeSource.markNow()
                val result = resultOf(block)
                val entry = LogEntry(action, args, started.elapsedNow().inWholeMilliseconds, result)
                // Whatever the action did, or failed halfway through doing, shows in the header.
                refreshSnapshot()
                _state.update { it.copy(log = (listOf(entry) + it.log).take(LOG_CAPACITY)) }
            } finally {
                _state.update { it.copy(running = null) }
            }
        }
    }

    private suspend fun resultOf(block: suspend () -> String): LogResult =
        try {
            LogResult.Ok(block())
        } catch (cancellation: CancellationException) {
            // Not a failure of the action: whoever cancelled it has to see it (CLAUDE.md §5).
            throw cancellation
        } catch (failure: WyrException) {
            LogResult.Err(failure.error, failure.message)
        } catch (crash: Throwable) {
            // Anything else is a bug, and surfacing bugs is what this screen is for.
            LogResult.Crash(crash::class.simpleName ?: "Throwable", crash.message)
        }

    private suspend fun refreshSnapshot() {
        val session = diagnostics.info()
        val queueSize = queue.count()
        _state.update { it.copy(session = session, queueSize = queueSize) }
    }

    companion object {
        const val LOG_CAPACITY: Int = 100
    }
}
