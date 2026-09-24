package io.ntole.wyr.dev.moderation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.moderation.AdminToken
import io.ntole.wyr.core.domain.moderation.ApproveSubmission
import io.ntole.wyr.core.domain.moderation.GetPendingSubmissions
import io.ntole.wyr.core.domain.moderation.RejectSubmission
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.dev.LogEntry
import io.ntole.wyr.dev.LogResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * Drives the console's *Moderation* section (CLAUDE.md §8d, *Moderation*): the pending queue read
 * through [GetPendingSubmissions], and each submission decided through [ApproveSubmission] or
 * [RejectSubmission], all with the admin token typed into the section.
 *
 * A ViewModel of its own beside `DevConsoleViewModel`, with its own log and its own one action at a
 * time, so the section stays in files of its own. The HTTP trace is the console's, since every
 * request goes through the one client, and it records no headers, so the token never shows there.
 * Nor does it show in this section's log, which names what was sent but never the token.
 *
 * Nothing here touches the player's session: the moderator is whoever holds the token, not a player.
 */
class ModerationConsoleViewModel(
    private val getPendingSubmissions: GetPendingSubmissions,
    private val approveSubmission: ApproveSubmission,
    private val rejectSubmission: RejectSubmission,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : ViewModel() {
    private val _state = MutableStateFlow(ModerationConsoleState())
    val state: StateFlow<ModerationConsoleState> = _state.asStateFlow()

    fun setAdminToken(text: String) = _state.update { it.copy(adminToken = SecretText(text)) }

    /**
     * Picks [category] for [questionId]'s approval, or unpicks it if it is picked. What is picked
     * replaces the author's categories on approval, and none keeps them.
     */
    fun toggleCategory(
        questionId: String,
        category: Category,
    ) = _state.update {
        val picked = it.newCategoriesOf(questionId)
        val toggled = if (category in picked) picked - category else picked + category
        // In declaration order, so the chips, the log and the request name them alike.
        it.copy(newCategories = it.newCategories + (questionId to toggled.sorted().toSet()))
    }

    fun setReason(
        questionId: String,
        text: String,
    ) = _state.update { it.copy(reasons = it.reasons + (questionId to text)) }

    /** The queue, as an action of its own. Nothing happens until [ModerationConsoleState.token]. */
    fun loadPending() {
        val token = _state.value.token ?: return
        perform("loadPending") { loadPending(token).summary() }
    }

    /**
     * Approves [questionId] under the categories picked for it, or under the author's when none are,
     * then reads the queue again. Nothing happens until [ModerationConsoleState.token].
     */
    fun approve(questionId: String) {
        val draft = _state.value
        val token = draft.token ?: return
        val categories = draft.newCategoriesOf(questionId)
        val args = "questionId=$questionId categories=${categories.logName()}"
        perform("approve", args, readsQueueWith = token) {
            approveSubmission(token, questionId, categories).summary()
        }
    }

    /**
     * Rejects [questionId] with the reason typed for it, then reads the queue again. Nothing happens
     * until [ModerationConsoleState.token] and [ModerationConsoleState.rejectionOf], so no reason the
     * server would refuse is sent.
     */
    fun reject(questionId: String) {
        val draft = _state.value
        val token = draft.token ?: return
        val reason = draft.rejectionOf(questionId) ?: return
        // Quoted as typed, so the whitespace trimmed off before it went shows in the log.
        val args = "questionId=$questionId reason=\"${draft.reasonOf(questionId)}\""
        perform("reject", args, readsQueueWith = token) {
            rejectSubmission(token, questionId, reason).summary()
        }
    }

    private suspend fun loadPending(token: AdminToken): List<Submission> {
        val pending = getPendingSubmissions(token)
        // What was picked for a submission still pending stays; for one no longer listed, it goes.
        val listed = pending.map { it.id }.toSet()
        _state.update {
            it.copy(
                pending = pending,
                newCategories = it.newCategories.filterKeys(listed::contains),
                reasons = it.reasons.filterKeys(listed::contains),
            )
        }
        return pending
    }

    /**
     * Runs [block] as the one action in flight and logs how it ended. [readsQueueWith] reads the queue
     * again afterwards with that token, for an action that can change it.
     */
    private fun perform(
        action: String,
        args: String = "",
        readsQueueWith: AdminToken? = null,
        block: suspend () -> String,
    ) = exclusively(action) {
        val started = timeSource.markNow()
        val result = resultOf(block)
        log(LogEntry(action, args, started.elapsedNow().inWholeMilliseconds, result))
        // After a failure too: a decision whose answer was lost may still have been made.
        if (readsQueueWith != null) refreshPending(readsQueueWith)
    }

    /**
     * Runs [work] as the one action in flight, named [action] in the section. A second action while
     * one runs is ignored, so the log reads in the order things happened.
     */
    private fun exclusively(
        action: String,
        work: suspend () -> Unit,
    ) {
        if (_state.value.isBusy) return
        _state.update { it.copy(running = action) }

        viewModelScope.launch {
            try {
                work()
            } finally {
                _state.update { it.copy(running = null) }
            }
        }
    }

    private suspend fun resultOf(block: suspend () -> String): LogResult =
        try {
            LogResult.Ok(block())
        } catch (cancellation: CancellationException) {
            // Not a failure of the action: whoever cancelled it has to see it (CLAUDE.md §5).
            throw cancellation
        } catch (failure: WyrException) {
            LogResult.Err(failure.error, failure.message)
        } catch (crash: Throwable) {
            // Anything else is a bug, and surfacing bugs is what this screen is for.
            LogResult.Crash(crash::class.simpleName ?: "Throwable", crash.message)
        }

    /**
     * Best effort: a failure keeps the queue shown, if any, and is logged as an entry of its own
     * instead of as the action's result.
     */
    private suspend fun refreshPending(token: AdminToken) {
        val started = timeSource.markNow()
        val result =
            resultOf {
                loadPending(token)
                "refreshed"
            }
        if (result !is LogResult.Ok) {
            log(LogEntry("refreshPending", "", started.elapsedNow().inWholeMilliseconds, result))
        }
    }

    /** How many are waiting, and which is next to decide. */
    private fun List<Submission>.summary(): String = "pending=$size" + (firstOrNull()?.let { " next=${it.id}" } ?: "")

    /** The submission as its author now sees it, categories filed and any reason trimmed. */
    private fun Submission.summary(): String =
        "submission=$id status=$status categories=${categories.logName()}" +
            (rejectionReason?.let { " reason=\"$it\"" } ?: "")

    /** None picked is the author's categories kept. */
    private fun Set<Category>.logName(): String = if (isEmpty()) KEEP else joinToString(",") { it.name }

    private fun log(entry: LogEntry) {
        _state.update { it.copy(log = (listOf(entry) + it.log).take(LOG_CAPACITY)) }
    }

    companion object {
        const val LOG_CAPACITY: Int = 50

        /** How the log names an approval that keeps the author's categories. */
        private const val KEEP = "keep"
    }
}
