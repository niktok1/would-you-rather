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

    /**
     * A question on screen, asked or revealed, with its likes as the server last counted them
     * (CLAUDE.md §8d, *Likes*): the feed's, until the answer to a like of the player's own.
     *
     * [isLiking] while the player's like or unlike of it is in flight. [likeError] is how the last
     * one failed, shown until the next is asked for or the question is answered.
     */
    sealed interface OnQuestion : PlayUiState {
        val question: Question
        val isLiking: Boolean
        val likeError: DomainError?

        /** While anything is in flight, when nothing else goes: one action at a time. */
        val isBusy: Boolean
    }

    /** [isSubmitting] while the vote on [question] is in flight. */
    data class Asking(
        override val question: Question,
        val isSubmitting: Boolean = false,
        override val isLiking: Boolean = false,
        override val likeError: DomainError? = null,
    ) : OnQuestion {
        override val isBusy: Boolean get() = isSubmitting || isLiking
    }

    data class Revealed(
        override val question: Question,
        val outcome: VoteOutcome,
        override val isLiking: Boolean = false,
        override val likeError: DomainError? = null,
    ) : OnQuestion {
        override val isBusy: Boolean get() = isLiking
    }

    /**
     * [lostVote] is the vote that failed when it is unknown whether it landed. Try again sends it
     * again as the same attempt instead of moving on.
     */
    data class Failed(
        val error: DomainError,
        val lostVote: PendingVote? = null,
    ) : PlayUiState
}

/**
 * Whether the Play screen takes a change of the categories played at once (CLAUDE.md §8d,
 * *Categories*): on a question with nothing in flight, asked or revealed, one action at a time as
 * the rest of the screen goes, and on a failure, where a selection with nothing to serve leaves the
 * player. Only then do the categories played open the Categories screen, and a selection played
 * there drop what is on screen and load a question from it. Not while a question loads, nor while a
 * vote, a skip or a like is in flight: that goes on, and the question after it is the new
 * selection's.
 */
val PlayUiState.canChangeCategories: Boolean
    get() =
        when (this) {
            PlayUiState.Loading -> false
            is PlayUiState.Failed -> true
            is PlayUiState.OnQuestion -> !isBusy
        }

/** One tap on a side, with the attempt made for it (CLAUDE.md §8d). */
data class PendingVote(
    val question: Question,
    val side: Side,
    val attempt: AttemptId,
)
