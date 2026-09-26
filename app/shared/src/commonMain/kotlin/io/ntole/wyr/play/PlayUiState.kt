package io.ntole.wyr.play

import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.report.ReportReason
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
     * A question on screen, asked or revealed, with its reactions as the server last counted them
     * (CLAUDE.md §8d, *Reactions*): the feed's, until the answer to a reaction of the player's own.
     *
     * [isReacting] while the player's reaction to it is in flight, and [isHiding] while a report or a
     * hide of it, from the menu on the top bar, is (§8d, *Reports*): each hides it. [rowError] is how
     * the last of those failed, in the row's failure slot, shown until the next is asked for or the
     * question is answered.
     */
    sealed interface OnQuestion : PlayUiState {
        val question: Question
        val isReacting: Boolean
        val isHiding: Boolean
        val rowError: DomainError?

        /** While anything is in flight, when nothing else goes: one action at a time. */
        val isBusy: Boolean
    }

    /** [isSubmitting] while the vote on [question] is in flight. */
    data class Asking(
        override val question: Question,
        val isSubmitting: Boolean = false,
        override val isReacting: Boolean = false,
        override val rowError: DomainError? = null,
        override val isHiding: Boolean = false,
    ) : OnQuestion {
        override val isBusy: Boolean get() = isSubmitting || isReacting || isHiding
    }

    data class Revealed(
        override val question: Question,
        val outcome: VoteOutcome,
        override val isReacting: Boolean = false,
        override val rowError: DomainError? = null,
        override val isHiding: Boolean = false,
    ) : OnQuestion {
        override val isBusy: Boolean get() = isReacting || isHiding
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
 * vote, a skip or a reaction is in flight: that goes on, and the question after it is the new
 * selection's.
 */
val PlayUiState.canChangeCategories: Boolean
    get() =
        when (this) {
            PlayUiState.Loading -> false
            is PlayUiState.Failed -> true
            is PlayUiState.OnQuestion -> !isBusy
        }

/**
 * Whether the menu about the question on the top bar is on (CLAUDE.md §8d, *The Play screen*): while a
 * question is on screen, asked or revealed, and nothing is in flight, one action at a time.
 */
val PlayUiState.canUseMenu: Boolean
    get() = this is PlayUiState.OnQuestion && !isBusy

/**
 * A choice from the Play screen's menu about the question on screen (CLAUDE.md §8d, *Reports*): report
 * it for a reason, hide it, or hide its author. Each hides it from the player for good.
 */
sealed interface MenuChoice {
    data class Report(
        val reason: ReportReason,
    ) : MenuChoice

    data object HideQuestion : MenuChoice

    data object HideAuthor : MenuChoice
}

/**
 * One tap on a side, with the attempt made for it (CLAUDE.md §8d), and how long the question had been
 * on screen by then, [answerMillis], which goes with the vote (§8b, *Personalization*) and to the
 * analytics (§8g): a retry sends the same, answered no slower.
 */
data class PendingVote(
    val question: Question,
    val side: Side,
    val attempt: AttemptId,
    val answerMillis: Long? = null,
)
