package io.ntole.wyr.admin.moderation

import io.ntole.wyr.core.domain.moderation.AdminToken
import io.ntole.wyr.core.domain.moderation.RejectionReason
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.Submission
import kotlin.jvm.JvmInline

/**
 * Everything the moderation app shows: the admin token as typed, the pending queue, and what the
 * moderator has picked or typed for each pending submission.
 *
 * Nothing is read until the moderator asks, since no token has been typed yet. One action runs at a
 * time ([running]), so what the screens show changes in the order things happened.
 */
data class ModerationState(
    /**
     * The admin token exactly as typed, held here, in memory, and nowhere else: not with the screen's
     * saved state, nor anywhere on the device, so it is typed again once the app closes, and Lock
     * clears it (CLAUDE.md §8d, *Moderation*). [SecretText] keeps it out of this state's `toString`.
     */
    val adminToken: SecretText = SecretText(""),
    val pending: PendingQueue = PendingQueue(),
    /** For each pending submission, by id, the approval's categories and the rejection's reason. */
    val drafts: Map<String, DecisionDraft> = emptyMap(),
    /** The action in flight, or `null` when idle. */
    val running: Running? = null,
) {
    val isBusy: Boolean get() = running != null

    /**
     * The token every request sends, or `null` while what is typed cannot be one (blank, or more than
     * visible ASCII once trimmed), which keeps every request from going.
     */
    val token: AdminToken? get() = AdminToken.of(adminToken.text)

    /** Whether a request can go now: a token, and nothing else in flight. */
    val canSend: Boolean get() = !isBusy && token != null

    fun draftOf(questionId: String): DecisionDraft = drafts[questionId] ?: DecisionDraft()

    /**
     * The reason to reject [questionId] with, or `null` while the one typed breaks a rule the server
     * holds a reason to, which keeps its Reject off: the server refuses such a reason as a malformed
     * request (CLAUDE.md §8d, *Moderation*).
     */
    fun rejectionOf(questionId: String): RejectionReason? = RejectionReason.of(draftOf(questionId).reason)
}

/**
 * The submissions waiting for a decision, oldest first, as the server last listed them, or `null`
 * until a read works. It is read on Load pending and again after every decision, whatever became of
 * it: a decision changes the queue, and one whose answer was lost, or that another moderator beat,
 * may have changed it too. A read that fails keeps what was listed and says why in [failure].
 */
data class PendingQueue(
    val submissions: List<Submission>? = null,
    /** Why the last read failed, or `null` once one works. */
    val failure: Failure? = null,
    /**
     * Why each decision that failed failed, by the submission's id, shown under it, or above the
     * queue once it is no longer listed. Kept until the next decision on it, or Load pending.
     */
    val failures: Map<String, ItemFailure> = emptyMap(),
    /** What the last decision that worked did, in a line, until the next action. */
    val notice: String? = null,
)

/**
 * What the moderator has picked for one pending submission: the [categories] an approval files it
 * under in place of the author's (none keeps the author's), and the [reason] to reject it with,
 * exactly as typed.
 */
data class DecisionDraft(
    val categories: Set<Category> = emptySet(),
    val reason: String = "",
)

/** Why an action on one question failed, and the question's options, to name it by. */
data class ItemFailure(
    val question: String,
    val failure: Failure,
)

/** The action in flight, and the question it acts on, if it acts on one. */
data class Running(
    val action: Action,
    val questionId: String? = null,
)

enum class Action {
    LOAD_PENDING,
    APPROVE,
    REJECT,
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
