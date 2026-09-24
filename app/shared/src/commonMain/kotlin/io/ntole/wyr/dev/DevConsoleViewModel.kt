package io.ntole.wyr.dev

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.like.QuestionLikes
import io.ntole.wyr.core.domain.like.SetLike
import io.ntole.wyr.core.domain.player.GetPlayerStats
import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.question.GetNextQuestion
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.question.QuestionCache
import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.domain.question.SkipQuestion
import io.ntole.wyr.core.domain.session.SessionDiagnostics
import io.ntole.wyr.core.domain.session.SessionRepository
import io.ntole.wyr.core.domain.vote.AttemptId
import io.ntole.wyr.core.domain.vote.CastVote
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.VoteOutcome
import io.ntole.wyr.core.network.environment.WyrEnvironment
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
    environment: WyrEnvironment,
    apiBaseUrl: String,
    private val sessions: SessionRepository,
    private val diagnostics: SessionDiagnostics,
    private val questions: QuestionRepository,
    private val queue: QuestionCache,
    private val getNextQuestion: GetNextQuestion,
    private val skipQuestion: SkipQuestion,
    private val castVote: CastVote,
    private val getPlayerStats: GetPlayerStats,
    private val setLike: SetLike,
    httpTrace: HttpTrace,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : ViewModel() {
    private val _state = MutableStateFlow(DevConsoleState(environment = environment, apiBaseUrl = apiBaseUrl))
    val state: StateFlow<DevConsoleState> = _state.asStateFlow()

    /** Plain data from the network layer, never a DTO, so it is passed through untouched. */
    val httpExchanges: StateFlow<List<HttpExchange>> = httpTrace.exchanges

    init {
        // Followed rather than snapshotted: the repository outlives this ViewModel, so the selection
        // it holds is the truth, whoever made it.
        viewModelScope.launch {
            questions.categories.collect { categories -> _state.update { it.copy(categories = categories) } }
        }

        // One action like any other, so no vote can land while the first stats read is in flight.
        // The header first, since it needs no network. Then the stats, which ensure a session and so
        // can mint one, and the header again, to show it.
        exclusively("refreshStats") {
            refreshSnapshot()
            refreshStats()
            refreshSnapshot()
        }
    }

    fun ensureSession() = perform("ensureSession") { "playerId=${sessions.ensure()}" }

    /**
     * A fresh player from nothing. The queue is reset between dropping the session and minting
     * the next one, so the new player never resumes the old one's queue.
     *
     * The categories stay selected: they are what the console asks the feed for, not anything of
     * the old player's, and the Category row keeps showing them. So the new player's first question
     * is already from them.
     */
    fun newGuest() =
        perform("newGuest", readsStats = true) {
            sessions.clear()
            _state.update {
                it.copy(question = null, lastOutcome = null, lastOutcomePlayerId = null, lastVote = null, stats = null)
            }
            questions.reset()
            val playerId = sessions.ensure()
            "playerId=$playerId ${loadQuestion().summary()}"
        }

    fun nextQuestion() = perform("nextQuestion") { loadQuestion().summary() }

    /**
     * Records the skip of the question on screen, then loads the next one (CLAUDE.md §8d). The
     * skipped question is not due for the rest of the cycle and comes back in the next. A skip
     * changes what is due, so the stats are read again after.
     *
     * A skip the server did not record does not keep the player on the question. Its failure is
     * logged as a `recordSkip` entry of its own, and the next question loads anyway, the skip's entry
     * ending `skip=unrecorded`. The question then stays due, so the feed can serve it again this
     * cycle, and Skip can be tried on it then.
     */
    fun skip() {
        val questionId = _state.value.question?.id ?: return
        perform("skip", args = "questionId=$questionId", readsStats = true) {
            val recorded = recordSkip(questionId)
            loadQuestion().summary() + if (recorded) "" else " skip=unrecorded"
        }
    }

    /**
     * Likes the question on screen, or unlikes it if the player likes it (CLAUDE.md §8d), then shows
     * it with its likes as the server answered them. A like pays the question's author, who may be
     * this player, so the stats are read again after, a failed like's too: its answer may be what was
     * lost.
     *
     * Which to ask for is read off the question on screen, and the request sets the like rather than
     * toggling it. So a like whose answer was lost leaves the question as it was, and pressing again
     * asks for the like again, which the server holds once however many times it lands.
     */
    fun toggleLike() {
        val question = _state.value.question ?: return
        val liked = !question.likedByMe
        perform("setLike", args = "questionId=${question.id} liked=$liked", readsStats = true) {
            // Before it goes out, since a like whose answer is lost may still have landed. With no
            // read yet to have measured the likes since the last outcome, the first to work would
            // count this one too.
            _state.update {
                val unmeasured = it.lastOutcome != null && it.likesReceivedAtOutcome == null
                it.copy(likeSentBeforeMeasure = it.likeSentBeforeMeasure || unmeasured)
            }
            val likes = setLike(question.id, liked)
            _state.update { state ->
                val shown = state.question
                // Only ever onto the question the server says it answered for.
                if (shown?.id != likes.questionId) {
                    state
                } else {
                    state.copy(question = shown.copy(likeCount = likes.likeCount, likedByMe = likes.likedByMe))
                }
            }
            likes.summary()
        }
    }

    /**
     * Adds [category] to the categories the feed is filtered to, or takes it out if it is in them,
     * then loads a question from the new selection. Taking out the last one lifts the filter, since
     * none selected is every category (CLAUDE.md §8d).
     *
     * Toggled on the repository's own selection, which is the truth, whoever made it.
     */
    fun toggleCategory(category: Category) {
        val selected = questions.categories.value
        val toggled = if (category in selected) selected - category else selected + category
        // In declaration order, so the log and the Category row name them in the order the chips are.
        selectCategories(toggled.sorted().toSet())
    }

    /** Lifts the filter, so the feed serves every category again, then loads a question from it. */
    fun selectAllCategories() = selectCategories(emptySet())

    /**
     * Filters the feed to [categories], then loads a question from them. A change drops the queue,
     * so that question is already the new selection's. The categories are one pool played within the
     * player's cycle: once nothing in any of them is due, they are served again (CLAUDE.md §8d).
     */
    private fun selectCategories(categories: Set<Category>) =
        perform("selectCategories", args = "categories=${categories.logName()}") {
            questions.setCategories(categories)
            loadQuestion().summary()
        }

    fun resetQueue() =
        perform("resetQueue") {
            questions.reset()
            "queue emptied"
        }

    /**
     * The stats again, as an action of their own. Next question, which asks the feed for more, does
     * not read them, so this is how to watch the next cycle start once the last question due is
     * answered (CLAUDE.md §8d): the cycle stays finished until the feed is next asked for questions.
     * Skip reads them after its next question, so a skip that asked the feed shows the start itself.
     */
    fun readStats() = perform("readStats") { loadStats().summary() }

    /** A new attempt, because every tap is an answer of its own (CLAUDE.md §8d). */
    fun vote(side: Side) {
        val question = _state.value.question ?: return
        send("vote", SentVote(question.id, side, AttemptId.random()))
    }

    /** Any id at all, so the 404 path, and answering any question again, can be provoked on purpose. */
    fun voteById(
        questionId: String,
        side: Side,
    ) = send("voteById", SentVote(questionId.trim(), side, AttemptId.random()))

    /**
     * The last vote sent, again and as the same attempt, whatever became of it. The server replays
     * it if it is still the latest answer to that question, and takes it as an answer if the first
     * never landed (CLAUDE.md §8d).
     */
    fun retryLastVote() {
        val vote = _state.value.lastVote ?: return
        send("retryLastVote", vote)
    }

    /**
     * Answers [count] questions in a row, alternating A and B, each as an answer of its own. The
     * quick way to the loop: past the size of the pool, the feed serves answered questions again
     * (CLAUDE.md §8d). Each answer is logged as it lands, and a failure ends the run as its result.
     */
    fun answerMany(count: Int) =
        perform("answerMany", args = "n=$count", readsStats = true) {
            require(count in 1..MAX_ANSWER_MANY) { "n must be in 1..$MAX_ANSWER_MANY, was $count" }
            var looped = 0
            repeat(count) { index ->
                val started = timeSource.markNow()
                val question = loadQuestion()
                if (question.answeredBefore) looped++
                val vote = SentVote(question.id, if (index % 2 == 0) Side.A else Side.B, AttemptId.random())
                val outcome = cast(vote)
                val elapsed = started.elapsedNow().inWholeMilliseconds
                val step = LogResult.Ok("${question.summary()} ${outcome.summary()}")
                log(LogEntry("answer", "${index + 1}/$count ${vote.args()}", elapsed, step))
            }
            "answered=$count looped=$looped total=${_state.value.lastOutcome?.totalPoints}"
        }

    private fun send(
        action: String,
        vote: SentVote,
    ) = perform(action, args = vote.args(), readsStats = true) { cast(vote).summary() }

    private suspend fun cast(vote: SentVote): VoteOutcome {
        // Before it goes out, so a vote whose response is lost can still be retried.
        _state.update { it.copy(lastVote = vote) }
        val outcome = castVote(vote.questionId, vote.side, vote.attempt)
        // After the vote, not before: a vote refused for a session the server has stopped accepting
        // went out again as a fresh guest, and was paid to them.
        val paidTo = sessions.currentPlayerId()
        // The stats read before this outcome no longer describe the server, so they go rather than
        // be compared with it, until the read that follows every vote brings them back. The likes
        // received are measured from that read on, so the previous outcome's measure goes too, and
        // with it any like sent before one was taken.
        _state.update {
            it.copy(
                lastOutcome = outcome,
                lastOutcomePlayerId = paidTo,
                stats = null,
                likesReceivedAtOutcome = null,
                likeSentBeforeMeasure = false,
            )
        }
        return outcome
    }

    private suspend fun loadQuestion(): Question {
        val question = getNextQuestion()
        _state.update { it.copy(question = question) }
        return question
    }

    private suspend fun loadStats(): PlayerStats {
        val stats = getPlayerStats()
        _state.update {
            // The first read after an outcome sets the likes later reads are measured against, unless
            // a like went out before it, which it may count already.
            val measures = it.lastOutcome != null && !it.likeSentBeforeMeasure
            val atOutcome = it.likesReceivedAtOutcome ?: if (measures) stats.likesReceived else null
            it.copy(stats = stats, likesReceivedAtOutcome = atOutcome)
        }
        return stats
    }

    /**
     * Runs [block] as the one action in flight and logs how it ended. [readsStats] reads the stats
     * again afterwards, for an action that can change them.
     */
    private fun perform(
        action: String,
        args: String = "",
        readsStats: Boolean = false,
        block: suspend () -> String,
    ) = exclusively(action) {
        val started = timeSource.markNow()
        val result = resultOf(block)
        log(LogEntry(action, args, started.elapsedNow().inWholeMilliseconds, result))
        // After a failure too: a vote whose answer was lost may still have landed.
        if (readsStats) refreshStats()
        // Whatever the action did, or failed halfway through doing, shows in the header.
        refreshSnapshot()
    }

    /**
     * Runs [work] as the one action in flight, named [action] in the header. A second action while
     * one runs is ignored, so the log reads in the order things happened.
     */
    private fun exclusively(
        action: String,
        work: suspend () -> Unit,
    ) {
        if (_state.value.isBusy) return
        _state.update { it.copy(running = action) }

        viewModelScope.launch {
            try {
                work()
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

    /**
     * Best effort. Reading the header touches storage, which can throw (a browser with site data
     * blocked throws on every localStorage read), so a failure keeps the previous header and is
     * logged as an entry of its own instead of escaping viewModelScope.
     */
    private suspend fun refreshSnapshot() {
        val started = timeSource.markNow()
        val result =
            resultOf {
                val session = diagnostics.info()
                val queueSize = queue.count()
                _state.update { it.copy(session = session, queueSize = queueSize) }
                "refreshed"
            }
        if (result !is LogResult.Ok) {
            log(LogEntry("refreshHeader", "", started.elapsedNow().inWholeMilliseconds, result))
        }
    }

    /**
     * Best effort, as [refreshStats]: a failure is logged as an entry of its own instead of ending
     * the action it is part of. Returns whether the server recorded the skip.
     */
    private suspend fun recordSkip(questionId: String): Boolean {
        val started = timeSource.markNow()
        val result =
            resultOf {
                skipQuestion(questionId)
                "recorded"
            }
        if (result !is LogResult.Ok) {
            log(LogEntry("recordSkip", "questionId=$questionId", started.elapsedNow().inWholeMilliseconds, result))
        }
        return result is LogResult.Ok
    }

    /**
     * Best effort, as [refreshSnapshot]: a failure keeps the stats shown, if any, and is logged as an
     * entry of its own instead of as the action's result.
     */
    private suspend fun refreshStats() {
        val started = timeSource.markNow()
        val result =
            resultOf {
                loadStats()
                "refreshed"
            }
        if (result !is LogResult.Ok) {
            log(LogEntry("refreshStats", "", started.elapsedNow().inWholeMilliseconds, result))
        }
    }

    /** A question the feed looped back to says so, which is how the loop shows in the log. */
    private fun Question.summary(): String = "question=$id" + if (answeredBefore) " looped" else ""

    private fun Set<Category>.logName(): String = if (isEmpty()) ALL_CATEGORIES else joinToString(",") { it.name }

    private fun SentVote.args(): String = "questionId=$questionId side=$side attempt=${attempt.value}"

    private fun VoteOutcome.summary(): String = "+$pointsAwarded total=$totalPoints" + if (replayed) " replayed" else ""

    private fun QuestionLikes.summary(): String = "question=$questionId likes=$likeCount likedByMe=$likedByMe"

    private fun PlayerStats.summary(): String =
        "total=$totalPoints answers=$answersGiven questions=$questionsAnswered cycle=$cycle due=$dueThisCycle " +
            "likes=$likesReceived"

    private fun log(entry: LogEntry) {
        _state.update { it.copy(log = (listOf(entry) + it.log).take(LOG_CAPACITY)) }
    }

    companion object {
        const val LOG_CAPACITY: Int = 100

        /** Half the log, so one run never pushes everything before it out. */
        const val MAX_ANSWER_MANY: Int = LOG_CAPACITY / 2

        /** How the log names a selection of no categories, which is every category. */
        private const val ALL_CATEGORIES = "all"
    }
}
