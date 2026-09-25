package io.ntole.wyr.submit

import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.OptionProblem
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionRules
import kotlin.time.Duration

/**
 * What the Submit screen shows (CLAUDE.md §8d, *Submitting*): the question being written, and the
 * player's own submissions below it.
 *
 * What is typed lives here, in memory, as typed: trimming is the server's, and [SubmissionRules]
 * says what it would refuse.
 */
data class SubmitState(
    val optionA: String = "",
    val optionB: String = "",
    /** The categories the question is to be filed under, never [Category.OTHER]. */
    val categories: Set<Category> = emptySet(),
    /** The player's submissions, newest first, as last read, or null until a read works. */
    val submissions: List<Submission>? = null,
    /** Whether the last Submit stored its question, until the next action starts. */
    val sent: Boolean = false,
    /** Why the last Submit stored nothing, until the next action starts. It shows under the form. */
    val submitFailure: SubmitFailure? = null,
    /**
     * Why the last read of the list failed, until the next action starts. It shows under the list,
     * with Try again, whatever the action before it ended in: a list never read has nothing else to
     * show, and one read before may lack a question the failed Submit stored after all.
     */
    val listFailure: SubmitFailure? = null,
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

    /** Whether Submit can go: both options pass the rules, a category is picked, nothing in flight. */
    val canSubmit: Boolean
        get() =
            !isBusy && categories.isNotEmpty() && !sameOptions &&
                SubmissionRules.optionProblem(optionA) == null && SubmissionRules.optionProblem(optionB) == null

    private fun problemOf(option: String): OptionProblem? =
        option.takeIf { it.isNotEmpty() }?.let(SubmissionRules::optionProblem)
}

/** What the Submit screen can be busy doing. */
enum class SubmitAction {
    /** Reading the player's own submissions. */
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
