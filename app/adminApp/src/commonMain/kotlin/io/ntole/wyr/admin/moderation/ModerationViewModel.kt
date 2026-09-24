package io.ntole.wyr.admin.moderation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.moderation.AdminToken
import io.ntole.wyr.core.domain.moderation.ApproveSubmission
import io.ntole.wyr.core.domain.moderation.GetPendingSubmissions
import io.ntole.wyr.core.domain.moderation.GetQuestions
import io.ntole.wyr.core.domain.moderation.ModeratedQuestion
import io.ntole.wyr.core.domain.moderation.QuestionCursor
import io.ntole.wyr.core.domain.moderation.QuestionFilter
import io.ntole.wyr.core.domain.moderation.RejectSubmission
import io.ntole.wyr.core.domain.moderation.RestoreQuestion
import io.ntole.wyr.core.domain.moderation.RetireQuestion
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.SubmissionStatus
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

    fun approve(
        questionId: String,
        from: Screen,
    )

    fun reject(
        questionId: String,
        from: Screen,
    )

    fun toggleStatusFilter(status: SubmissionStatus)

    fun toggleCategoryFilter(category: Category)

    fun clearFilter()

    fun loadQuestions()

    fun loadMore()

    fun askToRetire(questionId: String)

    fun cancelRetire()

    fun confirmRetire()

    fun restore(questionId: String)
}

/**
 * Drives the moderation app (CLAUDE.md §8d, *Moderation*): the admin token, held in memory here and
 * nowhere else; the pending queue, decided through [ApproveSubmission] and [RejectSubmission]; and
 * the list of every question, read through [GetQuestions] a page at a time, whose approved questions
 * [RetireQuestion] takes out of play once the moderator confirms it and whose retired ones
 * [RestoreQuestion] puts back. A pending question in the list is decided as in the queue.
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
    private val getQuestions: GetQuestions,
    private val retireQuestion: RetireQuestion,
    private val restoreQuestion: RestoreQuestion,
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
     * Forgets the token and everything read with it: the queue, the list, what was picked and typed,
     * and every outcome. The action in flight is cancelled, so nothing it answers is shown. The list's
     * filter stays: it was never the server's.
     */
    override fun lock() {
        locks++
        inFlight?.cancel()
        inFlight = null
        _state.update { ModerationState(questions = QuestionList(filter = it.questions.filter)) }
    }

    /** Reads the queue, as an action of its own, starting the queue's outcomes afresh. */
    override fun loadPending() =
        exclusively(Running(Action.LOAD_PENDING)) { token ->
            _state.update { it.copy(pending = it.pending.copy(outcomes = Outcomes())) }
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
        it.copy(drafts = it.drafts + (questionId to draft.copy(categories = draft.categories.toggled(category))))
    }

    override fun setReason(
        questionId: String,
        text: String,
    ) = _state.update { it.copy(drafts = it.drafts + (questionId to it.draftOf(questionId).copy(reason = text))) }

    /** Approves [questionId] under the categories picked for it, or the author's when none are. */
    override fun approve(
        questionId: String,
        from: Screen,
    ) {
        val categories = _state.value.draftOf(questionId).categories
        decide(Action.APPROVE, questionId, from) { token ->
            val approved = approveSubmission(token, questionId, categories)
            "Approved ${optionsOf(approved.optionA, approved.optionB)} under ${namesOf(approved.categories)}."
        }
    }

    /**
     * Rejects [questionId] with the reason typed for it. Nothing is sent until
     * [ModerationState.rejectionOf] holds one, so no reason the server would refuse goes.
     */
    override fun reject(
        questionId: String,
        from: Screen,
    ) {
        val reason = _state.value.rejectionOf(questionId) ?: return
        decide(Action.REJECT, questionId, from) { token ->
            val rejected = rejectSubmission(token, questionId, reason)
            "Rejected ${optionsOf(rejected.optionA, rejected.optionB)}: ${rejected.rejectionReason.orEmpty()}"
        }
    }

    /**
     * Lists [status] too, or no longer; none listed is every status. Never
     * [SubmissionStatus.OTHER], which names nothing the server can list by.
     */
    override fun toggleStatusFilter(status: SubmissionStatus) {
        if (status !in LISTABLE_STATUSES) return
        refilter { it.copy(statuses = it.statuses.toggled(status)) }
    }

    /**
     * Lists questions filed under [category] too, or no longer; none is every category. Never
     * [Category.OTHER], which names nothing the server can list by.
     */
    override fun toggleCategoryFilter(category: Category) {
        if (category !in Category.selectable) return
        refilter { it.copy(categories = it.categories.toggled(category)) }
    }

    /** Lists every question again. */
    override fun clearFilter() = refilter { QuestionFilter() }

    /** Reads the list's first page at its filter, as an action of its own, starting its outcomes afresh. */
    override fun loadQuestions() =
        exclusively(Running(Action.LOAD_QUESTIONS)) { token ->
            _state.update { it.copy(questions = it.questions.copy(outcomes = Outcomes())) }
            readList(token, shown = 0)
        }

    /** Reads the page after those listed, at the filter they were read at. */
    override fun loadMore() {
        val list = _state.value.questions
        val after = list.next ?: return
        exclusively(Running(Action.LOAD_MORE)) { token ->
            val failure =
                failureOf {
                    val page = getQuestions(token, list.filter, after)
                    _state.update {
                        val listed = it.questions.questions.orEmpty() + page.questions
                        it.copy(questions = it.questions.copy(questions = listed, next = page.next, failure = null))
                    }
                }
            if (failure != null) _state.update { it.copy(questions = it.questions.copy(failure = failure)) }
        }
    }

    /**
     * Asks the moderator to confirm retiring [questionId], an approved question the list shows.
     * Nothing is sent until [confirmRetire].
     */
    override fun askToRetire(questionId: String) {
        val current = _state.value
        val listed = current.questions.questions?.firstOrNull { it.id == questionId } ?: return
        if (!current.canSend || listed.status != SubmissionStatus.APPROVED) return
        _state.update { it.copy(retiring = questionId) }
    }

    override fun cancelRetire() = _state.update { it.copy(retiring = null) }

    /** Retires the question [askToRetire] asked about, then reads the list again. */
    override fun confirmRetire() {
        val current = _state.value
        val questionId = current.retiring ?: return
        if (!current.canSend) return
        _state.update { it.copy(retiring = null) }
        move(Action.RETIRE, questionId) { token ->
            val retired = retireQuestion(token, questionId)
            "Retired ${optionsOf(retired.optionA, retired.optionB)}: served to nobody until restored."
        }
    }

    /** Restores the retired question [questionId], then reads the list again. */
    override fun restore(questionId: String) =
        move(Action.RESTORE, questionId) { token ->
            val restored = restoreQuestion(token, questionId)
            "Restored ${optionsOf(restored.optionA, restored.optionB)}: served again."
        }

    /**
     * Sends a decision on [questionId] from [from], which answers with the line to show once it is
     * made, then reads the queue again, and the list if it was read, whatever became of it: a decision
     * whose answer was lost may still have been made, and one another moderator beat has changed both.
     */
    private fun decide(
        action: Action,
        questionId: String,
        from: Screen,
        send: suspend (AdminToken) -> String,
    ) = acting(Running(action, questionId), from) { token ->
        val failure =
            failureOf {
                val notice = send(token)
                // Decided, so nothing is left to pick for it.
                _state.update { it.noticed(from, notice).copy(drafts = it.drafts - questionId) }
            }
        readQueue(token)
        if (_state.value.questions.questions != null) rereadList(token)
        failure
    }

    /**
     * Retires or restores [questionId], which answers with the line to show once it is done, then
     * reads the list again, whatever became of it: a question another moderator moved first answers
     * `WRONG_STATUS`, and the list then shows where it stands.
     */
    private fun move(
        action: Action,
        questionId: String,
        send: suspend (AdminToken) -> String,
    ) = acting(Running(action, questionId), Screen.QUESTIONS) { token ->
        val failure =
            failureOf {
                val notice = send(token)
                _state.update { it.noticed(Screen.QUESTIONS, notice) }
            }
        rereadList(token)
        failure
    }

    /**
     * Runs [work], an action on the question [running] names, from [from]: its earlier failure there
     * goes as it starts, and the failure [work] answers, if any, is kept under it, named by the
     * question's options as they were listed when the action began.
     */
    private fun acting(
        running: Running,
        from: Screen,
        work: suspend (AdminToken) -> Failure?,
    ) {
        val questionId = requireNotNull(running.questionId) { "$running acts on no question" }
        val question = nameOf(questionId)
        exclusively(running) { token ->
            _state.update {
                it.withOutcomes(
                    from,
                ) { outcomes -> outcomes.copy(failures = outcomes.failures - questionId) }
            }
            val failure = work(token) ?: return@exclusively
            val failed = questionId to ItemFailure(question, failure)
            _state.update { it.withOutcomes(from) { outcomes -> outcomes.copy(failures = outcomes.failures + failed) } }
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

    /** Reads the list again, as deep as it was shown, so an action moves nobody's place in it. */
    private suspend fun rereadList(token: AdminToken) {
        val shown =
            _state.value.questions.questions
                ?.size ?: return
        readList(token, shown)
    }

    /**
     * Reads the list from its first page at its filter, page after page, until it holds at least
     * [shown] questions or there are no more; at least one page. A read that fails keeps what was
     * listed and says why.
     */
    private suspend fun readList(
        token: AdminToken,
        shown: Int,
    ) {
        val filter = _state.value.questions.filter
        val failure =
            failureOf {
                val listed = mutableListOf<ModeratedQuestion>()
                var next: QuestionCursor? = null
                do {
                    val page = getQuestions(token, filter, next)
                    listed += page.questions
                    next = page.next
                    // An empty page that claims another is the server's bug, not a reason to loop.
                } while (next != null && listed.size < shown && page.questions.isNotEmpty())
                _state.update {
                    it
                        .copy(
                            questions = it.questions.copy(questions = listed, next = next, failure = null),
                        ).pruned()
                }
            }
        if (failure != null) _state.update { it.copy(questions = it.questions.copy(failure = failure)) }
    }

    /**
     * Changes the list's filter, dropping what was read at the one before, and its outcomes. Not
     * while an action runs, whose answer is for the filter it was sent with.
     */
    private fun refilter(change: (QuestionFilter) -> QuestionFilter) {
        if (_state.value.isBusy) return
        _state.update { it.copy(questions = QuestionList(filter = change(it.questions.filter))).pruned() }
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
        // A notice says what the last action did, so the next one clears it wherever it was shown.
        _state.update { it.noticed(Screen.PENDING, null).noticed(Screen.QUESTIONS, null).copy(running = running) }

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
    private fun nameOf(questionId: String): String {
        val current = _state.value
        val submission = current.pending.submissions?.firstOrNull { it.id == questionId }
        val question = current.questions.questions?.firstOrNull { it.id == questionId }
        return submission?.let { optionsOf(it.optionA, it.optionB) }
            ?: question?.let { optionsOf(it.optionA, it.optionB) }
            ?: questionId
    }

    private fun ModerationState.noticed(
        screen: Screen,
        notice: String?,
    ): ModerationState = withOutcomes(screen) { it.copy(notice = notice) }

    private fun ModerationState.withOutcomes(
        screen: Screen,
        change: (Outcomes) -> Outcomes,
    ): ModerationState =
        when (screen) {
            Screen.PENDING -> copy(pending = pending.copy(outcomes = change(pending.outcomes)))
            Screen.QUESTIONS -> copy(questions = questions.copy(outcomes = change(questions.outcomes)))
        }

    /** Drops what was picked for a question no longer pending on either screen. */
    private fun ModerationState.pruned(): ModerationState {
        val pendingIds =
            pending.submissions
                .orEmpty()
                .map { it.id }
                .toSet() + questions.pendingIds
        return copy(drafts = drafts.filterKeys(pendingIds::contains))
    }

    /** This set with [value] in it, or out of it if it was in, in declaration order. */
    private fun <T : Comparable<T>> Set<T>.toggled(value: T): Set<T> =
        (if (value in this) this - value else this + value).sorted().toSet()
}
