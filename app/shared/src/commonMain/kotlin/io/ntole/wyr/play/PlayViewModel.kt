package io.ntole.wyr.play

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ntole.wyr.core.domain.category.CategoryRepository
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.like.SetLike
import io.ntole.wyr.core.domain.player.GetPlayerStats
import io.ntole.wyr.core.domain.question.GetNextQuestion
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.domain.question.SkipQuestion
import io.ntole.wyr.core.domain.vote.AttemptId
import io.ntole.wyr.core.domain.vote.CastVote
import io.ntole.wyr.core.domain.vote.Side
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

/**
 * How long after a reveal lands a tap on a card is still taken for the answering tap's double, and
 * does not go on (CLAUDE.md §8d, *The Play screen*; provisional). The second tap of a double tap
 * comes at most 300 ms after the first, and so at most that long after a quick answer lands.
 */
internal const val REVEAL_HOLD_MILLIS: Long = 500

class PlayViewModel(
    private val getNextQuestion: GetNextQuestion,
    private val castVote: CastVote,
    private val skipQuestion: SkipQuestion,
    private val setLike: SetLike,
    private val getPlayerStats: GetPlayerStats,
    private val questions: QuestionRepository,
    categoryList: CategoryRepository,
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

    init {
        load()
        // Categories played on the Categories screen (CLAUDE.md §8d, *Categories*) drop the question
        // on screen, asked or answered, or the failure, and a question from them loads: load, not
        // next, which goes on only from the reveal. Only while the categories may change here: a load
        // or a vote, a skip or a like in flight goes on, and the question after it is the new
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
                    PlayUiState.Asking(getNextQuestion())
                } catch (failure: WyrException) {
                    PlayUiState.Failed(failure.error)
                }

            // Top the queue back up for the *next* question, without making this one wait for it.
            // A failure here is not the player's problem — the queue simply refills later.
            launch { runCatching { questions.prefetch() } }
        }
    }

    fun choose(side: Side) {
        val asking = _state.value as? PlayUiState.Asking ?: return

        // Guard against a double tap turning into two answers, and against answering mid-like.
        if (asking.isBusy) return
        // One attempt per tap (CLAUDE.md §8d), kept for any retry of this vote.
        submit(PendingVote(asking.question, side, AttemptId.random()))
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
        // Not while its vote is in flight, when it is being answered, nor while its like is.
        if (asking.isBusy) return
        // Loading at once, so a second tap finds nothing to skip.
        _state.value = PlayUiState.Loading

        viewModelScope.launch {
            try {
                skipQuestion(asking.question.id)
            } catch (unrecorded: WyrException) {
                // Moved on all the same (above). Only a WyrException: a cancellation must go on up.
            }
            load()
        }
    }

    /**
     * Likes the question on screen, or unlikes it if the player likes it (CLAUDE.md §8d, *Likes*),
     * before answering or after, then shows it with its likes as the server answered them.
     *
     * Which to ask for is read off the question on screen, and the request sets the like rather
     * than toggling it. Nothing changes on screen until the server answers, so a like that failed
     * leaves the question as it was, with the failure beside it, and pressing again asks for the
     * same like again, which the server holds once however many times it lands. Nothing else goes
     * while it is in flight.
     */
    fun toggleLike() {
        val shown = _state.value as? PlayUiState.OnQuestion ?: return
        if (shown.isBusy) return
        val question = shown.question
        val liking = shown.withLike(isLiking = true, likeError = null)
        _state.value = liking

        viewModelScope.launch {
            val settled =
                try {
                    val likes = setLike(question.id, !question.likedByMe)
                    // Only ever onto the question the server says it answered for.
                    val answered =
                        if (likes.questionId == question.id) {
                            question.copy(likeCount = likes.likeCount, likedByMe = likes.likedByMe)
                        } else {
                            question
                        }
                    liking.withLike(question = answered, isLiking = false, likeError = null)
                } catch (failure: WyrException) {
                    liking.withLike(isLiking = false, likeError = failure.error)
                }
            // Unless the player has moved on meanwhile, when it is no longer the question on screen.
            _state.update { current -> if (current == liking) settled else current }
        }
    }

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
     * Reads the player's points, each time the Play screen is shown: they move meanwhile on other
     * screens, a login or a logout, a submission, and with other players' likes of the player's
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
                    val outcome = castVote(vote.question.id, vote.side, vote.attempt)
                    // The vote's total is the newest the server has told: a read still in flight
                    // may be older.
                    pointsRead?.cancel()
                    _points.value = outcome.totalPoints
                    revealHold = viewModelScope.launch { delay(REVEAL_HOLD_MILLIS) }
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
                    PlayUiState.Failed(failure.error, lostVote = vote.takeIf { failure.error == DomainError.NETWORK })
                }
        }
    }

    private fun PlayUiState.OnQuestion.withLike(
        question: Question = this.question,
        isLiking: Boolean,
        likeError: DomainError?,
    ): PlayUiState.OnQuestion =
        when (this) {
            is PlayUiState.Asking -> copy(question = question, isLiking = isLiking, likeError = likeError)
            is PlayUiState.Revealed -> copy(question = question, isLiking = isLiking, likeError = likeError)
        }
}
