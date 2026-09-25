package io.ntole.wyr.play

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.like.SetLike
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.question.GetNextQuestion
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.domain.question.SkipQuestion
import io.ntole.wyr.core.domain.vote.AttemptId
import io.ntole.wyr.core.domain.vote.CastVote
import io.ntole.wyr.core.domain.vote.Side
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PlayViewModel(
    private val getNextQuestion: GetNextQuestion,
    private val castVote: CastVote,
    private val skipQuestion: SkipQuestion,
    private val setLike: SetLike,
    private val questions: QuestionRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<PlayUiState>(PlayUiState.Loading)
    val state: StateFlow<PlayUiState> = _state.asStateFlow()

    /**
     * The categories played, as the repository holds them, so they always say what the next fetch
     * asks for (CLAUDE.md §8d, *Categories*): none is every category. In memory for the app's life,
     * as the repository keeps them, so a launch starts on every category again.
     */
    val categories: StateFlow<Set<Category>> = questions.categories

    private val _picking = MutableStateFlow<Set<Category>?>(null)

    /** What the open category picker has ticked, not played yet, or `null` while it is closed. */
    val picking: StateFlow<Set<Category>?> = _picking.asStateFlow()

    init {
        next()
    }

    fun next() {
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
     * A skip that fails moves on all the same, and says nothing, as the dev console's did: the
     * player asked not to answer this question, and keeping them on it, or on an error they can do
     * nothing about, would make them deal with it anyway. All an unrecorded skip loses is that the
     * question stays due, so the feed may serve it again this cycle, where Skip works on it again.
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
            next()
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
     * Opens the category picker on the categories played now. Nothing changes until [applyCategories]:
     * one change, and so one reload, however many categories the player ticks on the way.
     *
     * Only when the categories may change ([canChangeCategories]), since applying them drops the
     * question on screen: not while a question loads, nor while a vote, a skip or a like is in flight.
     */
    fun openCategories() {
        if (!_state.value.canChangeCategories) return
        _picking.value = questions.categories.value
    }

    /**
     * Ticks [category] in the open picker, or unticks it if it is ticked. Unticking the last one is
     * every category, since none selected is every category (CLAUDE.md §8d, *Categories*).
     * [Category.OTHER] cannot be ticked: no feed can be filtered to it.
     */
    fun toggleCategory(category: Category) {
        if (category !in Category.selectable) return
        _picking.update { ticked -> ticked?.let { if (category in it) it - category else it + category } }
    }

    /** Unticks every category in the open picker, which is every category. */
    fun selectAllCategories() {
        _picking.update { ticked -> ticked?.let { emptySet() } }
    }

    /**
     * Plays the categories ticked in the open picker, and closes it. A new selection drops the
     * question on screen, answered or not, and loads the next one from it: the repository drops its
     * queue on the change (CLAUDE.md §8d, *Categories*), so it is the new selection's. What the feed
     * then serves is the server's rule; a selection it has nothing in shows as out of questions, where
     * the categories can change again. The selection already played changes nothing, and the question
     * on screen stays.
     *
     * A change the screen cannot take now ([canChangeCategories]) is refused and the picker stays open
     * with what it has ticked, to be played once nothing is in flight. From a failure, the vote it lost,
     * if any, is not sent again: the player has moved on.
     */
    fun applyCategories() {
        val ticked = _picking.value ?: return
        if (ticked == questions.categories.value) {
            _picking.value = null
            return
        }
        if (!_state.value.canChangeCategories) return
        _picking.value = null
        // Loading at once, so nothing else goes, and a second tap finds nothing to apply.
        _state.value = PlayUiState.Loading

        viewModelScope.launch {
            // Before the fetch, so the question loaded is already the new selection's.
            questions.setCategories(ticked)
            next()
        }
    }

    /** Closes the category picker, and what it had ticked goes unplayed. */
    fun closeCategories() {
        _picking.value = null
    }

    /**
     * Sends a lost vote again as the same attempt, so that if the first one did land the server
     * replays it rather than paying for it twice. After any other failure, moves on.
     */
    fun retry() {
        // Only from a failure: a second tap on Try again finds the retry already under way.
        val failed = _state.value as? PlayUiState.Failed ?: return
        val lostVote = failed.lostVote
        if (lostVote == null) next() else submit(lostVote)
    }

    private fun submit(vote: PendingVote) {
        _state.value = PlayUiState.Asking(vote.question, isSubmitting = true)

        viewModelScope.launch {
            _state.value =
                try {
                    PlayUiState.Revealed(
                        question = vote.question,
                        outcome = castVote(vote.question.id, vote.side, vote.attempt),
                    )
                } catch (failure: WyrException) {
                    // Already voted is not really a failure to show: the question is spent, so move
                    // the player on rather than stranding them on an error they cannot resolve.
                    if (failure.error == DomainError.ALREADY_VOTED) {
                        next()
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
