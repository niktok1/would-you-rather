package io.ntole.wyr.admin.moderation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.moderation.AdminToken
import io.ntole.wyr.core.domain.moderation.ApproveSubmission
import io.ntole.wyr.core.domain.moderation.GetPendingSubmissions
import io.ntole.wyr.core.domain.moderation.RejectSubmission
import io.ntole.wyr.core.domain.question.Category
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the moderator can do from the app's screens. [ModerationViewModel] does it; a screen takes
 * this rather than a callback per button.
 */
interface ModerationActions {
    fun setAdminToken(text: String)

    fun lock()

    fun loadPending()

    fun toggleApprovalCategory(
        questionId: String,
        category: Category,
    )

    fun setReason(
        questionId: String,
        text: String,
    )

    fun approve(questionId: String)

    fun reject(questionId: String)
}

/**
 * Drives the moderation app (CLAUDE.md §8d, *Moderation*): the admin token, held in memory here and
 * nowhere else, and the pending queue, decided through [ApproveSubmission] and [RejectSubmission].
 *
 * Nothing here has a player session, or could make one: the moderator is whoever holds the token,
 * and the app's wiring binds no session at all (`moderationDataModule`). Every request carries the
 * token as typed, trimmed, and only once it can be one ([ModerationState.token]).
 *
 * One action runs at a time; a second one asked for meanwhile is ignored, so the screens change in
 * the order things happened. [lock] is the exception: it always works, and it cancels the action in
 * flight, so nothing read with the token is shown once it is gone.
 */
class ModerationViewModel(
    private val getPendingSubmissions: GetPendingSubmissions,
    private val approveSubmission: ApproveSubmission,
    private val rejectSubmission: RejectSubmission,
) : ViewModel(),
    ModerationActions {
    private val _state = MutableStateFlow(ModerationState())
    val state: StateFlow<ModerationState> = _state.asStateFlow()

    /** The action in flight, cancelled by [lock]. */
    private var inFlight: Job? = null

    /**
     * How many times the app has been locked. An action cancelled by a lock may finish its cleanup
     * after the next action started, and must then leave that one's state alone.
     */
    private var locks = 0

    override fun setAdminToken(text: String) = _state.update { it.copy(adminToken = SecretText(text)) }

    /**
     * Forgets the token and everything read with it: the queue, what was picked and typed for it, and
     * why anything failed. The action in flight is cancelled, so nothing it answers is shown.
     */
    override fun lock() {
        locks++
        inFlight?.cancel()
        inFlight = null
        _state.value = ModerationState()
    }

    /** Reads the queue, as an action of its own, starting its failures afresh. */
    override fun loadPending() =
        exclusively(Running(Action.LOAD_PENDING)) { token ->
            _state.update { it.copy(pending = it.pending.copy(failures = emptyMap(), notice = null)) }
            readQueue(token)
        }

    /**
     * Picks [category] for [questionId]'s approval, or unpicks it if it is picked. What is picked
     * replaces the author's categories on approval, and none keeps them.
     */
    override fun toggleApprovalCategory(
        questionId: String,
        category: Category,
    ) = _state.update {
        val draft = it.draftOf(questionId)
        val picked = if (category in draft.categories) draft.categories - category else draft.categories + category
        // In declaration order, so the screen and the request name them alike.
        it.copy(drafts = it.drafts + (questionId to draft.copy(categories = picked.sorted().toSet())))
    }

    override fun setReason(
        questionId: String,
        text: String,
    ) = _state.update { it.copy(drafts = it.drafts + (questionId to it.draftOf(questionId).copy(reason = text))) }

    /** Approves [questionId] under the categories picked for it, or the author's when none are. */
    override fun approve(questionId: String) {
        val categories = _state.value.draftOf(questionId).categories
        decide(Action.APPROVE, questionId) { token ->
            val approved = approveSubmission(token, questionId, categories)
            "Approved ${optionsOf(approved.optionA, approved.optionB)} under ${namesOf(approved.categories)}."
        }
    }

    /**
     * Rejects [questionId] with the reason typed for it. Nothing is sent until
     * [ModerationState.rejectionOf] holds one, so no reason the server would refuse goes.
     */
    override fun reject(questionId: String) {
        val reason = _state.value.rejectionOf(questionId) ?: return
        decide(Action.REJECT, questionId) { token ->
            val rejected = rejectSubmission(token, questionId, reason)
            "Rejected ${optionsOf(rejected.optionA, rejected.optionB)}: ${rejected.rejectionReason.orEmpty()}"
        }
    }

    /**
     * Sends a decision on [questionId], which answers with the line to show once it is made, then
     * reads the queue again, whatever became of it: a decision whose answer was lost may still have
     * been made, and one another moderator beat has changed the queue too.
     */
    private fun decide(
        action: Action,
        questionId: String,
        send: suspend (AdminToken) -> String,
    ) {
        val question = nameOf(questionId)
        exclusively(Running(action, questionId)) { token ->
            _state.update { it.copy(pending = it.pending.copy(failures = it.pending.failures - questionId)) }
            val failure =
                failureOf {
                    val notice = send(token)
                    // Decided, so nothing is left to pick for it.
                    _state.update {
                        it.copy(
                            pending = it.pending.copy(notice = notice),
                            drafts = it.drafts - questionId,
                        )
                    }
                }
            if (failure != null) {
                val failed = questionId to ItemFailure(question, failure)
                _state.update { it.copy(pending = it.pending.copy(failures = it.pending.failures + failed)) }
            }
            readQueue(token)
        }
    }

    /** Reads the queue; a read that fails keeps what was listed and says why. */
    private suspend fun readQueue(token: AdminToken) {
        val failure =
            failureOf {
                val submissions = getPendingSubmissions(token)
                _state.update { it.copy(pending = it.pending.copy(submissions = submissions, failure = null)).pruned() }
            }
        if (failure != null) _state.update { it.copy(pending = it.pending.copy(failure = failure)) }
    }

    /**
     * Runs [work] with the token as the one action in flight. Nothing happens without a token, or
     * while another action runs.
     */
    private fun exclusively(
        running: Running,
        work: suspend (AdminToken) -> Unit,
    ) {
        val current = _state.value
        val token = current.token ?: return
        if (current.isBusy) return
        val lockedAtStart = locks
        _state.update { it.copy(running = running, pending = it.pending.copy(notice = null)) }

        inFlight =
            viewModelScope.launch {
                try {
                    work(token)
                } finally {
                    // A lock meanwhile has already cleared it, and may have let another action start.
                    if (lockedAtStart == locks) _state.update { it.copy(running = null) }
                }
            }
    }

    /** Runs [block], and says why it failed, or `null` when it worked. */
    private suspend fun failureOf(block: suspend () -> Unit): Failure? =
        try {
            block()
            null
        } catch (cancellation: CancellationException) {
            // Not a failure of the action: whoever cancelled it has to see it (CLAUDE.md §5).
            throw cancellation
        } catch (refused: WyrException) {
            Failure.Refused(refused.error, refused.retryAfter, refused.message)
        } catch (bug: Exception) {
            Failure.Bug(bug::class.simpleName ?: "Exception", bug.message)
        }

    /** The question [questionId] names, by its options, for a failure shown once it is not listed. */
    private fun nameOf(questionId: String): String =
        _state.value.pending.submissions
            ?.firstOrNull { it.id == questionId }
            ?.let { optionsOf(it.optionA, it.optionB) }
            ?: questionId

    /** Drops what was picked for a submission no longer pending anywhere listed. */
    private fun ModerationState.pruned(): ModerationState {
        val pendingIds =
            pending.submissions
                .orEmpty()
                .map { it.id }
                .toSet()
        return copy(drafts = drafts.filterKeys(pendingIds::contains))
    }
}
