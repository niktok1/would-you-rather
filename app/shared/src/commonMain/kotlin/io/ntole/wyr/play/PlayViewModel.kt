package io.ntole.wyr.play

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.question.GetNextQuestion
import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.domain.vote.AttemptId
import io.ntole.wyr.core.domain.vote.CastVote
import io.ntole.wyr.core.domain.vote.Side
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlayViewModel(
    private val getNextQuestion: GetNextQuestion,
    private val castVote: CastVote,
    private val questions: QuestionRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<PlayUiState>(PlayUiState.Loading)
    val state: StateFlow<PlayUiState> = _state.asStateFlow()

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

        // Guard against a double tap turning into two answers.
        if (asking.isSubmitting) return
        // One attempt per tap (CLAUDE.md §8d), kept for any retry of this vote.
        submit(PendingVote(asking.question, side, AttemptId.random()))
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
}
