package io.ntole.wyr.play

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ntole.wyr.core.domain.analytics.Analytics
import io.ntole.wyr.core.domain.analytics.AnalyticsEvent
import io.ntole.wyr.core.domain.analytics.AnalyticsProperty
import io.ntole.wyr.core.domain.category.CategoryRepository
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.player.GetPlayerStats
import io.ntole.wyr.core.domain.question.GetNextQuestion
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.domain.question.SkipQuestion
import io.ntole.wyr.core.domain.reaction.Reaction
import io.ntole.wyr.core.domain.reaction.SetReaction
import io.ntole.wyr.core.domain.report.HideAuthor
import io.ntole.wyr.core.domain.report.HideQuestion
import io.ntole.wyr.core.domain.report.ReportQuestion
import io.ntole.wyr.core.domain.vote.AttemptId
import io.ntole.wyr.core.domain.vote.CastVote
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.share.ShareOutcome
import io.ntole.wyr.share.SharedQuestion
import io.ntole.wyr.share.questionShared
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * How long after a reveal lands a tap on a card is still taken for the answering tap's double, and
 * does not go on (CLAUDE.md §8d, *The Play screen*; provisional). The second tap of a double tap
 * comes at most 300 ms after the first, and so at most that long after a quick answer lands.
 */
internal const val REVEAL_HOLD_MILLIS: Long = 500

/**
 * Drives the Play screen (CLAUDE.md §8d, *The Play screen*), and tells [analytics] what the player did
 * there (§8g): each question shown, answered after how long, skipped, reacted to, reported or hidden,
 * and every failure shown, by the question's id and its categories', never its text. [timeSource]
 * measures how long a question was on screen, only while the Play screen is shown ([screenShown],
 * [screenHidden]).
 */
class PlayViewModel(
    private val getNextQuestion: GetNextQuestion,
    private val castVote: CastVote,
    private val skipQuestion: SkipQuestion,
    private val setReaction: SetReaction,
    private val reportQuestion: ReportQuestion,
    private val hideQuestion: HideQuestion,
    private val hideAuthor: HideAuthor,
    private val getPlayerStats: GetPlayerStats,
    private val questions: QuestionRepository,
    categoryList: CategoryRepository,
    private val analytics: Analytics,
    timeSource: TimeSource.WithComparableMarks,
) : ViewModel() {
    private val _state = MutableStateFlow<PlayUiState>(PlayUiState.Loading)
    val state: StateFlow<PlayUiState> = _state.asStateFlow()

    /**
     * The categories played, as the repository holds them, so they always say what the next fetch
     * asks for (CLAUDE.md §8d, *Categories*): none is every category. In memory for the app's life,
     * as the repository keeps them, so a launch starts on every category again. Beside them, every
     * category as last read from the server, to name them by.
     */
    val categories: StateFlow<PlayedCategories> =
        combine(questions.categories, categoryList.categories, ::PlayedCategories)
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                PlayedCategories(questions.categories.value, categoryList.categories.value),
            )

    private val _points = MutableStateFlow<Int?>(null)

    /**
     * The player's points as the server last reported them (CLAUDE.md §8c), never worked out here:
     * read each time the screen is shown ([refreshPoints]) and taken from each vote's answer, `null`
     * until the first of them.
     */
    val points: StateFlow<Int?> = _points.asStateFlow()

    /** The read of the points in flight, if any, which a vote's answer makes stale. */
    private var pointsRead: Job? = null

    /** Active for [REVEAL_HOLD_MILLIS] from the moment a reveal lands, while [next] waits. */
    private var revealHold: Job? = null

    /** How long the question asked has been on screen, for how long the player took over it. */
    private val onQuestion = ScreenStopwatch(timeSource)

    init {
        load()
        // Categories played on the Categories screen (CLAUDE.md §8d, *Categories*) drop the question
        // on screen, asked or answered, or the failure, and a question from them loads: load, not
        // next, which goes on only from the reveal. Only while the categories may change here: a load
        // or a vote, a skip or a reaction in flight goes on, and the question after it is the new
        // selection's, since the change dropped the queue. Undispatched, so a change made before this
        // runs is not taken for the one played now.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            questions.categories.drop(1).collect { if (_state.value.canChangeCategories) load() }
        }
    }

    /**
     * The next question, from the reveal (CLAUDE.md §8d, *The Play screen*): only once the question
     * on screen is answered and nothing is in flight, one action at a time. Before answering, the
     * way on is an answer or Skip, never this; and a second tap finds the next question loading.
     *
     * Not in the first [REVEAL_HOLD_MILLIS] of the reveal either: the cards that answer are the way
     * on from it, so the second tap of a double tap, landing after a quick answer, would otherwise
     * skip the reveal it had just brought.
     */
    fun next() {
        val revealed = _state.value as? PlayUiState.Revealed ?: return
        if (revealed.isBusy) return
        if (revealHold?.isActive == true) return
        load()
    }

    private fun load() {
        _state.value = PlayUiState.Loading

        viewModelScope.launch {
            _state.value =
                try {
                    PlayUiState.Asking(getNextQuestion()).also { asked -> questionShown(asked.question) }
                } catch (failure: WyrException) {
                    reportShown(failure.error, ACTION_QUESTION)
                    PlayUiState.Failed(failure.error)
                }

            // Top the queue back up for the *next* question, without making this one wait for it.
            // A failure here is not the player's problem — the queue simply refills later.
            launch { runCatching { questions.prefetch() } }
        }
    }

    fun choose(side: Side) {
        val asking = _state.value as? PlayUiState.Asking ?: return

        // Guard against a double tap turning into two answers, and against answering mid-reaction.
        if (asking.isBusy) return
        // One attempt per tap (CLAUDE.md §8d), kept for any retry of this vote, as is how long it took.
        submit(PendingVote(asking.question, side, AttemptId.random(), answerMillis = millisOnQuestion()))
    }

    /**
     * Skips the question being asked, then shows the next one (CLAUDE.md §8d, *Skipping*). A skip
     * earns nothing and leaves the tally alone, and the server keeps the question out of the rest of
     * the player's cycle, so it comes back in the next one.
     *
     * A skip that fails moves on all the same, and says nothing: the player asked not to answer this
     * question, and keeping them on it, or on an error they can do nothing about, would make them
     * deal with it anyway. All an unrecorded skip loses is that the question stays due, so the feed
     * may serve it again this cycle, where Skip works on it again.
     * A failure that is not the skip's alone, such as being offline, shows on the next question's
     * fetch, or on its vote.
     */
    fun skip() {
        val asking = _state.value as? PlayUiState.Asking ?: return
        // Not while its vote is in flight, when it is being answered, nor while a reaction is.
        if (asking.isBusy) return
        // Loading at once, so a second tap finds nothing to skip.
        _state.value = PlayUiState.Loading

        val millis = millisOnQuestion()

        viewModelScope.launch {
            val recorded =
                try {
                    skipQuestion(asking.question.id)
                    true
                } catch (unrecorded: WyrException) {
                    // Moved on all the same (above). Only a WyrException: a cancellation must go on up.
                    false
                }
            analytics.track(
                AnalyticsEvent.QUESTION_SKIPPED,
                about(asking.question) +
                    mapOf(AnalyticsProperty.DURATION_MS to millis, AnalyticsProperty.RECORDED to recorded),
            )
            load()
        }
    }

    /**
     * Makes [reaction] what the player thinks of the question on screen, [Reaction.NONE] to take
     * theirs back (CLAUDE.md §8d, *Reactions*), before answering or after, then shows it with its
     * reactions as the server answered them. The screen asks for the thumb tapped, or for none when it
     * is the one the player holds.
     *
     * The request sets the reaction rather than toggling it. Nothing changes on screen until the server
     * answers, so a reaction that failed leaves the question as it was, with the failure beside it, and
     * pressing again asks for the same again, which the server holds once however many times it lands.
     * Nothing else goes while it is in flight.
     */
    fun react(reaction: Reaction) {
        val shown = _state.value as? PlayUiState.OnQuestion ?: return
        if (shown.isBusy) return
        val question = shown.question
        val reacting = shown.with(isReacting = true, rowError = null)
        _state.value = reacting

        viewModelScope.launch {
            val settled =
                try {
                    val reactions = setReaction(question.id, reaction)
                    // Only ever onto the question the server says it answered for.
                    val answered =
                        if (reactions.questionId == question.id) {
                            question.copy(
                                likeCount = reactions.likeCount,
                                dislikeCount = reactions.dislikeCount,
                                myReaction = reactions.myReaction,
                            )
                        } else {
                            question
                        }
                    analytics.track(
                        AnalyticsEvent.REACTION_SET,
                        about(question) +
                            mapOf(
                                AnalyticsProperty.REACTION to reaction.name.lowercase(),
                                AnalyticsProperty.ANSWERED to (shown is PlayUiState.Revealed),
                            ),
                    )
                    reacting.with(question = answered, isReacting = false, rowError = null)
                } catch (failure: WyrException) {
                    reportShown(failure.error, ACTION_REACTION)
                    reacting.with(isReacting = false, rowError = failure.error)
                }
            // Unless the player has moved on meanwhile, when it is no longer the question on screen.
            _state.update { current -> if (current == reacting) settled else current }
        }
    }

    /**
     * Reports the question on screen, or hides it or its author, as the player chose from the menu on
     * the top bar (CLAUDE.md §8d, *Reports*), asked or revealed. Each hides it from the player for good,
     * so once the server has it the next question shows, with nothing more said, as after a skip. One
     * that failed leaves the question on screen, and says why in the row's failure slot, as a reaction's
     * failure does; choosing again sends the same again, which the server holds once. Nothing else goes
     * while it is in flight.
     */
    fun pickFromMenu(choice: MenuChoice) {
        val shown = _state.value as? PlayUiState.OnQuestion ?: return
        if (shown.isBusy) return
        val question = shown.question
        val hiding = shown.with(isHiding = true, rowError = null)
        _state.value = hiding

        viewModelScope.launch {
            try {
                when (choice) {
                    is MenuChoice.Report -> reportQuestion(question.id, choice.reason)
                    MenuChoice.HideQuestion -> hideQuestion(question.id)
                    MenuChoice.HideAuthor -> hideAuthor(question.id)
                }
                analytics.track(eventOf(choice), about(question) + propertiesOf(choice, shown))
                // Nothing else could go meanwhile, so it is still the question on screen.
                if (_state.value == hiding) load()
            } catch (failure: WyrException) {
                reportShown(failure.error, actionOf(choice))
                _state.update { current ->
                    if (current == hiding) hiding.with(isHiding = false, rowError = failure.error) else current
                }
            }
        }
    }

    /**
     * [question] was shared from the Play screen's dialog, [withResults] or not, as [outcome] says
     * (CLAUDE.md §8d, *Sharing*): the analytics hear of it. Nothing on screen changes.
     */
    fun shared(
        question: SharedQuestion,
        withResults: Boolean,
        outcome: ShareOutcome,
    ) = analytics.questionShared(question, withResults, outcome)

    /**
     * Sends a lost vote again as the same attempt, so that if the first one did land the server
     * replays it rather than paying for it twice. After any other failure, moves on.
     */
    fun retry() {
        // Only from a failure: a second tap on Try again finds the retry already under way.
        val failed = _state.value as? PlayUiState.Failed ?: return
        val lostVote = failed.lostVote
        if (lostVote == null) load() else submit(lostVote)
    }

    /**
     * The Play screen is shown again, from another screen or from the background: the time on the
     * question asked goes on from now.
     */
    fun screenShown() = onQuestion.shown()

    /**
     * The Play screen is hidden, for another screen or the background: the time on the question asked
     * stops, so a detour is not counted as time the player took over it.
     */
    fun screenHidden() = onQuestion.hidden()

    /**
     * Reads the player's points, each time the Play screen is shown: they move meanwhile on other
     * screens, a login or a logout, a submission, and with other players' reactions to the player's
     * questions. A read that fails keeps the points shown and says nothing: the next vote's answer
     * brings them. A vote answered while a read is in flight wins, since the server may have
     * answered the read before it counted the vote.
     */
    fun refreshPoints() {
        pointsRead?.cancel()
        pointsRead =
            viewModelScope.launch {
                try {
                    _points.value = getPlayerStats().totalPoints
                } catch (unread: WyrException) {
                    // Kept as they were (above). Only a WyrException: a cancellation must go on up.
                }
            }
    }

    private fun submit(vote: PendingVote) {
        _state.value = PlayUiState.Asking(vote.question, isSubmitting = true)

        viewModelScope.launch {
            _state.value =
                try {
                    // The time it took goes with it, the first tap's on a retry (CLAUDE.md §8b, *Personalization*).
                    val outcome = castVote(vote.question.id, vote.side, vote.attempt, vote.answerMillis)
                    // The vote's total is the newest the server has told: a read still in flight
                    // may be older.
                    pointsRead?.cancel()
                    _points.value = outcome.totalPoints
                    revealHold = viewModelScope.launch { delay(REVEAL_HOLD_MILLIS) }
                    analytics.track(
                        AnalyticsEvent.QUESTION_ANSWERED,
                        about(vote.question) +
                            mapOf(
                                AnalyticsProperty.SIDE to vote.side.name,
                                AnalyticsProperty.ANSWER_MS to vote.answerMillis,
                                AnalyticsProperty.AGREED_WITH_MAJORITY to outcome.agreedWithMajority,
                            ),
                    )
                    PlayUiState.Revealed(question = vote.question, outcome = outcome)
                } catch (failure: WyrException) {
                    // Already voted is not really a failure to show: the question is spent, so move
                    // the player on rather than stranding them on an error they cannot resolve.
                    if (failure.error == DomainError.ALREADY_VOTED) {
                        load()
                        return@launch
                    }
                    // NETWORK is what retry safety is for: the vote may have landed, and only its
                    // response been lost. Anything else came back as an answer, and resending a vote
                    // the server refused would fail the same way every time, so Try again moves on.
                    reportShown(failure.error, ACTION_VOTE)
                    PlayUiState.Failed(failure.error, lostVote = vote.takeIf { failure.error == DomainError.NETWORK })
                }
        }
    }

    /** [question] is asked: the analytics hear of it, and the time the player takes over it starts. */
    private fun questionShown(question: Question) {
        onQuestion.start()
        analytics.track(AnalyticsEvent.QUESTION_SHOWN, about(question))
    }

    /**
     * How long the question asked has been on screen, while the Play screen was, in whole
     * milliseconds, or null if none is.
     */
    private fun millisOnQuestion(): Long? = onQuestion.elapsed()?.inWholeMilliseconds

    /** A failure the screen shows, [error], of [action], for the analytics. */
    private fun reportShown(
        error: DomainError,
        action: String,
    ) {
        analytics.track(
            AnalyticsEvent.ERROR_SHOWN,
            mapOf(AnalyticsProperty.CODE to error.name, AnalyticsProperty.ACTION to action),
        )
    }

    private fun PlayUiState.OnQuestion.with(
        question: Question = this.question,
        isReacting: Boolean = this.isReacting,
        isHiding: Boolean = this.isHiding,
        rowError: DomainError?,
    ): PlayUiState.OnQuestion =
        when (this) {
            is PlayUiState.Asking -> {
                copy(question = question, isReacting = isReacting, isHiding = isHiding, rowError = rowError)
            }

            is PlayUiState.Revealed -> {
                copy(question = question, isReacting = isReacting, isHiding = isHiding, rowError = rowError)
            }
        }
}

/** The event a menu choice that worked is reported as (CLAUDE.md §8g). */
private fun eventOf(choice: MenuChoice): String =
    when (choice) {
        is MenuChoice.Report -> AnalyticsEvent.QUESTION_REPORTED
        MenuChoice.HideQuestion -> AnalyticsEvent.QUESTION_HIDDEN
        MenuChoice.HideAuthor -> AnalyticsEvent.AUTHOR_HIDDEN
    }

/**
 * What a menu choice's event says of its own: a report's reason, and whether the question was answered,
 * [shown] revealed when the choice was made.
 */
private fun propertiesOf(
    choice: MenuChoice,
    shown: PlayUiState.OnQuestion,
): Map<String, Any?> {
    val reason = (choice as? MenuChoice.Report)?.let { mapOf(AnalyticsProperty.REASON to it.reason.name.lowercase()) }
    return reason.orEmpty() + mapOf(AnalyticsProperty.ANSWERED to (shown is PlayUiState.Revealed))
}

/** What a menu choice that failed is reported as having failed at (CLAUDE.md §8g). */
private fun actionOf(choice: MenuChoice): String =
    when (choice) {
        is MenuChoice.Report -> ACTION_REPORT
        MenuChoice.HideQuestion -> ACTION_HIDE_QUESTION
        MenuChoice.HideAuthor -> ACTION_HIDE_AUTHOR
    }

/** What an event says of [question]: its id and its categories', never its text (CLAUDE.md §8g). */
private fun about(question: Question): Map<String, Any?> =
    mapOf(AnalyticsProperty.QUESTION_ID to question.id, AnalyticsProperty.CATEGORIES to question.categories.toList())

private const val ACTION_QUESTION = "question"
private const val ACTION_VOTE = "vote"
private const val ACTION_REACTION = "reaction"
private const val ACTION_REPORT = "report"
private const val ACTION_HIDE_QUESTION = "hide_question"
private const val ACTION_HIDE_AUTHOR = "hide_author"
