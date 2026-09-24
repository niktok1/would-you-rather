package io.ntole.wyr.dev.moderation

import io.ntole.wyr.core.domain.moderation.AdminToken
import io.ntole.wyr.core.domain.moderation.RejectionReason
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.dev.LogEntry
import kotlin.jvm.JvmInline

/**
 * What the console's *Moderation* section shows: the admin token as typed, the pending queue, what
 * the moderator has picked for each pending submission, and a log of this section's actions in the
 * console's own style.
 *
 * Nothing is read when the section opens, since no token has been typed yet. [pending] is read on
 * Load pending and after every decision, whatever became of it: a decision changes the queue, and
 * one whose answer was lost, or that another moderator beat, may have changed it too. A read that
 * fails leaves it as it was, which may be none. Load pending is an action like any other, logged as
 * `loadPending` whether it works or not; the read after a decision is logged only when it fails, as
 * `refreshPending`.
 */
data class ModerationConsoleState(
    /**
     * The admin token exactly as typed, held here, in memory, and nowhere else: not with the screen's
     * saved state, nor anywhere on the device, so it is typed again once the app restarts
     * (CLAUDE.md §8d, *Moderation*). [SecretText] keeps it out of this state's `toString`.
     */
    val adminToken: SecretText = SecretText(""),
    /** The submissions waiting for a decision, oldest first, as last read, or `null` until a read works. */
    val pending: List<Submission>? = null,
    /**
     * For each pending submission, by id, the categories an approval files it under in place of the
     * author's. None, where every submission starts, keeps the author's.
     */
    val newCategories: Map<String, Set<Category>> = emptyMap(),
    /** For each pending submission, by id, the reason to reject it with, exactly as typed. */
    val reasons: Map<String, String> = emptyMap(),
    /** The action in flight, or `null` when idle. Only one runs at a time. */
    val running: String? = null,
    /** Newest first, at most [ModerationConsoleViewModel.LOG_CAPACITY] entries. */
    val log: List<LogEntry> = emptyList(),
) {
    val isBusy: Boolean get() = running != null

    /**
     * The token every request of this section sends, or `null` while what is typed cannot be one
     * (blank, or more than visible ASCII once trimmed), which keeps every one of its buttons off.
     */
    val token: AdminToken? get() = AdminToken.of(adminToken.text)

    fun newCategoriesOf(questionId: String): Set<Category> = newCategories[questionId].orEmpty()

    fun reasonOf(questionId: String): String = reasons[questionId].orEmpty()

    /**
     * The reason to reject [questionId] with, or `null` while the one typed breaks a rule the server
     * holds a reason to, which keeps its Reject off: the server refuses such a reason as a malformed
     * request (CLAUDE.md §8d, *Moderation*).
     */
    fun rejectionOf(questionId: String): RejectionReason? = RejectionReason.of(reasonOf(questionId))
}

/**
 * Text typed into a secret field. [toString] never shows it, so no dump of a state that holds it, in
 * a log line or a failed assertion, carries the secret.
 */
@JvmInline
value class SecretText(
    val text: String,
) {
    override fun toString(): String = "SecretText(redacted)"
}
