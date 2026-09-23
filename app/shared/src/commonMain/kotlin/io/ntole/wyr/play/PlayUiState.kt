package io.ntole.wyr.play

import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.vote.AttemptId
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.VoteOutcome

/**
 * What the play screen can be showing.
 *
 * Modelled as a sealed hierarchy so the screen cannot render a state that does not exist — there
 * is no way to be simultaneously loading and revealed, or revealed without an outcome.
 */
sealed interface PlayUiState {
    data object Loading : PlayUiState

    data class Asking(
        val question: Question,
        val isSubmitting: Boolean = false,
    ) : PlayUiState

    data class Revealed(
        val question: Question,
        val outcome: VoteOutcome,
    ) : PlayUiState

    /**
     * [lostVote] is the vote that failed when it is unknown whether it landed. Try again sends it
     * again as the same attempt instead of moving on.
     */
    data class Failed(
        val error: DomainError,
        val lostVote: PendingVote? = null,
    ) : PlayUiState
}

/** One tap on a side, with the attempt made for it (CLAUDE.md §8d). */
data class PendingVote(
    val question: Question,
    val side: Side,
    val attempt: AttemptId,
)
