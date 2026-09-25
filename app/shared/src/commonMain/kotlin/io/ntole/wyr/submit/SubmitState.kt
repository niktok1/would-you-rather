package io.ntole.wyr.submit

import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.submission.OptionProblem
import io.ntole.wyr.core.domain.submission.SubmissionRules
import kotlin.time.Duration

/**
 * What the Submit screen's form shows (CLAUDE.md §8d, *Submitting*): the question being written, the
 * categories it can be filed under, as the server lists them, and the player's points, since sending
 * it costs [SubmissionRules.SUBMISSION_COST]. The player's own submissions are on the Account screen,
 * My questions, which opens the form.
 *
 * What is typed lives here, in memory, as typed: trimming is the server's, and [SubmissionRules]
 * says what it would refuse.
 */
data class SubmitState(
    val optionA: String = "",
    val optionB: String = "",
    /** The ids of the categories the question is to be filed under, picked from [categoryOptions]. */
    val categories: Set<String> = emptySet(),
    /** Every category a question can be filed under, as last read from the server, oldest first. */
    val categoryOptions: List<Category> = emptyList(),
    /**
     * Why the last read of the categories failed, until one works. It shows under them, with Try
     * again, and the categories read before, if any, stay to pick from.
     */
    val categoriesFailure: SubmitFailure? = null,
    /** The player's points as last read, or null until a read works. */
    val points: Int? = null,
    /**
     * Whether the last Submit stored its question, until the form goes back to My questions for it
     * ([SubmitActions.leftForm]) or the next action starts.
     */
    val sent: Boolean = false,
    /** Why the last Submit stored nothing, until the next action starts. It shows under the form. */
    val submitFailure: SubmitFailure? = null,
    /**
     * Why the last read of the points failed, until the next action starts. It shows under Send, with
     * Try again, whatever the action before it ended in: Send waits on the points.
     */
    val pointsFailure: SubmitFailure? = null,
    /** The action in flight, or null when idle. Only one runs at a time. */
    val running: SubmitAction? = null,
) {
    val isBusy: Boolean get() = running != null

    /** While a question is being sent, the form is not to change: it is cleared once it is stored. */
    val isSubmitting: Boolean get() = running == SubmitAction.SUBMIT

    /** What the rules refuse in option A, or null while nothing is typed. */
    val optionAProblem: OptionProblem? get() = problemOf(optionA)

    /** What the rules refuse in option B, or null while nothing is typed. */
    val optionBProblem: OptionProblem? get() = problemOf(optionB)

    /** Whether the two options are the same by the rules, once each is one a question can have. */
    val sameOptions: Boolean
        get() =
            SubmissionRules.optionProblem(optionA) == null && SubmissionRules.optionProblem(optionB) == null &&
                SubmissionRules.sameOptions(optionA, optionB)

    /** Whether the points last read are fewer than a question costs, which the form says: not while none are read. */
    val tooFewPoints: Boolean get() = points != null && points < SubmissionRules.SUBMISSION_COST

    /**
     * Whether Submit can go: both options pass the rules, a category is picked, the points last read
     * pay for it, and nothing is in flight.
     */
    val canSubmit: Boolean
        get() =
            !isBusy && categories.isNotEmpty() && !sameOptions && points != null && !tooFewPoints &&
                SubmissionRules.optionProblem(optionA) == null && SubmissionRules.optionProblem(optionB) == null

    private fun problemOf(option: String): OptionProblem? =
        option.takeIf { it.isNotEmpty() }?.let(SubmissionRules::optionProblem)
}

/** What the Submit screen can be busy doing. */
enum class SubmitAction {
    /** Reading the categories and the player's points. */
    LOAD,
    SUBMIT,
}

/**
 * How an action failed. [retryAfter] is the wait the server named with a
 * [DomainError.RATE_LIMITED], or null.
 */
data class SubmitFailure(
    val error: DomainError,
    val retryAfter: Duration? = null,
)
