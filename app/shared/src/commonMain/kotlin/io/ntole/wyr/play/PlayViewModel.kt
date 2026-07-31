package io.ntole.wyr.play

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.question.GetNextQuestion
import io.ntole.wyr.core.domain.question.QuestionRepository
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

        // Guard against a double tap turning into two votes and an ALREADY_VOTED error.
        if (asking.isSubmitting) return
        _state.value = asking.copy(isSubmitting = true)

        viewModelScope.launch {
            _state.value =
                try {
                    PlayUiState.Revealed(
                        question = asking.question,
                        outcome = castVote(asking.question.id, side),
                    )
                } catch (failure: WyrException) {
                    // Already voted is not really a failure to show: the question is spent, so move
                    // the player on rather than stranding them on an error they cannot resolve.
                    if (failure.error == DomainError.ALREADY_VOTED) {
                        next()
                        return@launch
                    }
                    PlayUiState.Failed(failure.error)
                }
        }
    }

    fun retry() = next()
}
