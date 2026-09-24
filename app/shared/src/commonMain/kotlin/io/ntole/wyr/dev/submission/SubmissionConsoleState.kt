package io.ntole.wyr.dev.submission

import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.dev.LogEntry

/**
 * What the console's *Submit a question* section shows: the question being written, the author's
 * own submissions, and a log of this section's actions in the console's own style.
 *
 * [submissions] are read when the section opens, after every Submit, and on Refresh. A read that
 * fails leaves them as they were, which may be none. Refresh is an action like any other, logged as
 * `listSubmissions` whether it works or not; the other reads are logged only when they fail, as
 * `refreshSubmissions`.
 */
data class SubmissionConsoleState(
    /** Exactly as typed: trimming, like every rule about what an option may say, is the server's. */
    val optionA: String = "",
    val optionB: String = "",
    /** The categories the question is to be filed under, in declaration order. */
    val categories: Set<Category> = emptySet(),
    /** The author's submissions, newest first, as last read, or `null` until a read works. */
    val submissions: List<Submission>? = null,
    /**
     * The player [submissions] are, as the session stood once the read returned. Not always the one
     * shown in the console's header: New guest there changes the player without reading this list.
     */
    val listedFor: String? = null,
    /** The action in flight, or `null` when idle. Only one runs at a time. */
    val running: String? = null,
    /** Newest first, at most [SubmissionConsoleViewModel.LOG_CAPACITY] entries. */
    val log: List<LogEntry> = emptyList(),
) {
    val isBusy: Boolean get() = running != null

    /**
     * Whether there is a question to submit: both options typed and a category picked. The server
     * refuses a submission under no category as a malformed request (CLAUDE.md §8d), so the picker
     * must have one before Submit lets it go. Every other rule is left to the server, whose refusal
     * the log shows.
     */
    val canSubmit: Boolean
        get() = optionA.isNotBlank() && optionB.isNotBlank() && categories.isNotEmpty()
}
