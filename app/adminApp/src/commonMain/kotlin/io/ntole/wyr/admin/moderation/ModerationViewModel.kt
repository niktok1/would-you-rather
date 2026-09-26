package io.ntole.wyr.admin.moderation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ntole.wyr.core.domain.category.GetCategories
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.moderation.AddCategory
import io.ntole.wyr.core.domain.moderation.AdminToken
import io.ntole.wyr.core.domain.moderation.ApproveSubmission
import io.ntole.wyr.core.domain.moderation.BlockAuthor
import io.ntole.wyr.core.domain.moderation.DismissReports
import io.ntole.wyr.core.domain.moderation.GetPendingSubmissions
import io.ntole.wyr.core.domain.moderation.GetQuestions
import io.ntole.wyr.core.domain.moderation.GetReportedQuestions
import io.ntole.wyr.core.domain.moderation.ModeratedQuestion
import io.ntole.wyr.core.domain.moderation.QuestionCursor
import io.ntole.wyr.core.domain.moderation.QuestionFilter
import io.ntole.wyr.core.domain.moderation.RejectSubmission
import io.ntole.wyr.core.domain.moderation.RenameCategory
import io.ntole.wyr.core.domain.moderation.RestoreQuestion
import io.ntole.wyr.core.domain.moderation.RetireQuestion
import io.ntole.wyr.core.domain.moderation.UnblockAuthor
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
        categoryId: String,
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

    fun loadReports()

    fun dismiss(questionId: String)

    /** Asks the moderator to confirm blocking [authorId], the author of [questionId] as [from] shows it. */
    fun askToBlock(
        authorId: String,
        questionId: String,
        from: Screen,
    )

    fun setBlockReason(text: String)

    fun cancelBlock()

    fun confirmBlock()

    fun unblock(
        authorId: String,
        questionId: String,
        from: Screen,
    )

    fun toggleStatusFilter(status: SubmissionStatus)

    fun toggleCategoryFilter(categoryId: String)

    fun clearFilter()

    fun loadQuestions()

    fun loadMore()

    fun askToRetire(
        questionId: String,
        from: Screen,
    )

    fun cancelRetire()

    fun confirmRetire()

    fun restore(
        questionId: String,
        from: Screen,
    )

    fun loadCategories()

    /** Replaces what is typed for Add with [draft]. */
    fun editNewCategory(draft: CategoryDraft)

    fun saveNewCategory()

    fun startRenaming(categoryId: String)

    /** Replaces the names typed for the category being renamed with [draft]'s; its id stays. */
    fun editRenaming(draft: CategoryDraft)

    fun cancelRenaming()

    fun saveRenaming()
}

/**
 * Drives the moderation app (CLAUDE.md §8d, *Moderation*): the admin token, held in memory here and
 * nowhere else; the pending queue, decided through [ApproveSubmission] and [RejectSubmission]; and
 * the list of every question, read through [GetQuestions] a page at a time, whose approved questions
 * [RetireQuestion] takes out of play once the moderator confirms it and whose retired ones
 * [RestoreQuestion] puts back; and the reported questions, read through [GetReportedQuestions], whose
 * reports [DismissReports] clears and which are retired and restored as in the list. [BlockAuthor]
 * blocks a question's author, once the moderator confirms it, and [UnblockAuthor] lets them submit
 * again, from any of the three. A pending question in the list is decided as in the queue. The
 * categories, read through [GetCategories] before the queue or the list each Load reads, are what the
 * chips offer and what names a question's categories; [AddCategory] adds one and [RenameCategory]
 * puts its names right.
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
    private val getReportedQuestions: GetReportedQuestions,
    private val dismissReports: DismissReports,
    private val blockAuthor: BlockAuthor,
    private val unblockAuthor: UnblockAuthor,
    private val getCategories: GetCategories,
    private val addCategory: AddCategory,
    private val renameCategory: RenameCategory,
) : ViewModel(),
    ModerationActions {
    private val _state = MutableStateFlow(ModerationState())
    val state: StateFlow<ModerationState> = _state.asStateFlow()

    /** The action in flight, cancelled by [lock]. */
    private var inFlight: Job? = null

    override fun setAdminToken(text: String) = _state.update { it.copy(adminToken = SecretText(text)) }

    /**
     * Forgets the token and everything read with it: the queue, the list, what was picked and typed,
     * and every outcome, and, since [ModerationState.locks] moves on, the token field's undo history.
     * The action in flight is cancelled, so nothing it answers is shown. The list's filter stays: it
     * was never the server's. So do the categories as read, which are the same for everybody and need
     * no token; what was typed for one goes, with the rest typed.
     */
    override fun lock() {
        // Counted before the cancel, which may run the cancelled action's cleanup at once.
        _state.update {
            ModerationState(
                questions = QuestionList(filter = it.questions.filter),
                categories = CategoryList(categories = it.categories.categories, failure = it.categories.failure),
                locks = it.locks + 1,
            )
        }
        inFlight?.cancel()
        inFlight = null
    }

    /**
     * Reads the categories and then the queue, as an action of its own, starting the queue's outcomes
     * afresh.
     */
    override fun loadPending() =
        exclusively(Running(Action.LOAD_PENDING)) { token ->
            _state.update { it.copy(pending = it.pending.copy(outcomes = Outcomes())) }
            readCategories()
            readQueue(token)
        }

    /**
     * Picks the category [categoryId] for [questionId]'s approval, or unpicks it if it is picked. What
     * is picked replaces the author's categories on approval, and none keeps them.
     */
    override fun toggleApprovalCategory(
        questionId: String,
        categoryId: String,
    ) = _state.update {
        val draft = it.draftOf(questionId)
        it.copy(drafts = it.drafts + (questionId to draft.copy(categories = draft.categories.toggled(categoryId))))
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
            val names = namesOf(approved.categories, _state.value.categories.categories)
            "Approved ${optionsOf(approved.optionA, approved.optionB)} under $names."
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
     * Reads the categories and then the reported questions, as an action of its own, starting the
     * reports' outcomes afresh.
     */
    override fun loadReports() =
        exclusively(Running(Action.LOAD_REPORTS)) { token ->
            _state.update { it.copy(reports = it.reports.copy(outcomes = Outcomes())) }
            readCategories()
            readReports(token)
        }

    /**
     * Clears every report of the reported question [questionId], then reads the reports again,
     * whatever became of it: a dismissal takes the question off them, and one whose answer was lost
     * may have been made. Not after a refusal every request would meet ([refusesEveryRequest]).
     */
    override fun dismiss(questionId: String) {
        val question = nameOf(questionId)
        acting(Running(Action.DISMISS_REPORTS, questionId), Screen.REPORTS) { token ->
            val failure =
                failureOf {
                    dismissReports(token, questionId)
                    val notice = "Dismissed the reports of $question: it stays as it stands."
                    _state.update { it.noticed(Screen.REPORTS, notice) }
                }
            if (failure.refusesEveryRequest) return@acting failure
            readReports(token)
            failure
        }
    }

    /**
     * Asks the moderator to confirm blocking [authorId], the author of [questionId] as [from] shows
     * it, and to give the reason each of their pending questions is rejected with. Nothing is sent
     * until [confirmBlock].
     */
    override fun askToBlock(
        authorId: String,
        questionId: String,
        from: Screen,
    ) {
        if (!_state.value.canSend) return
        _state.update { it.copy(blocking = BlockDraft(authorId, questionId, from)) }
    }

    override fun setBlockReason(text: String) = _state.update { it.copy(blocking = it.blocking?.copy(reason = text)) }

    override fun cancelBlock() = _state.update { it.copy(blocking = null) }

    /**
     * Blocks the author [askToBlock] asked about, once [ModerationState.blockReason] holds a reason,
     * and keeps where the server answered they stand. Then reads again whatever was read, the queue,
     * the reported questions and the list as deep as it was shown, whatever became of it: a block
     * rejects each of the author's pending questions, and one whose answer was lost may have. Not
     * after a refusal every request would meet ([refusesEveryRequest]), which blocked nobody.
     */
    override fun confirmBlock() {
        val current = _state.value
        val draft = current.blocking ?: return
        val reason = current.blockReason ?: return
        if (!current.canSend) return
        _state.update { it.copy(blocking = null) }
        acting(Running(Action.BLOCK_AUTHOR, draft.questionId), draft.from) { token ->
            val failure =
                failureOf {
                    val block = blockAuthor(token, draft.authorId, reason)
                    _state.update {
                        it.standing(block.authorId, block.isBlocked).noticed(draft.from, blockedNoticeOf(block))
                    }
                }
            if (failure.refusesEveryRequest) return@acting failure
            val read = _state.value
            if (read.pending.submissions != null) readQueue(token)
            if (read.reports.reports != null) readReports(token)
            if (read.questions.questions != null) rereadList(token)
            failure
        }
    }

    /**
     * Lets [authorId], the author of [questionId] as [from] shows it, submit again, and keeps where
     * the server answered they stand. Nothing is read again: nothing the server lists shows it.
     */
    override fun unblock(
        authorId: String,
        questionId: String,
        from: Screen,
    ) = acting(Running(Action.UNBLOCK_AUTHOR, questionId), from) { token ->
        failureOf {
            val unblock = unblockAuthor(token, authorId)
            val notice = "Unblocked author ${shortAuthorOf(unblock.authorId)}: they may submit again."
            _state.update { it.standing(unblock.authorId, unblock.isBlocked).noticed(from, notice) }
        }
    }

    private fun ModerationState.standing(
        authorId: String,
        blocked: Boolean,
    ): ModerationState = copy(authors = authors + (authorId to blocked))

    /**
     * Lists [status] too, or no longer; none listed is every status. Never
     * [SubmissionStatus.OTHER], which names nothing the server can list by.
     */
    override fun toggleStatusFilter(status: SubmissionStatus) {
        if (status !in LISTABLE_STATUSES) return
        refilter { it.copy(statuses = it.statuses.toggled(status)) }
    }

    /** Lists questions filed under the category [categoryId] too, or no longer; none is every category. */
    override fun toggleCategoryFilter(categoryId: String) =
        refilter { it.copy(categories = it.categories.toggled(categoryId)) }

    /** Lists every question again. */
    override fun clearFilter() = refilter { QuestionFilter() }

    /**
     * Reads the categories and then the list's first page at its filter, as an action of its own,
     * starting its outcomes afresh.
     */
    override fun loadQuestions() =
        exclusively(Running(Action.LOAD_QUESTIONS)) { token ->
            _state.update { it.copy(questions = it.questions.copy(outcomes = Outcomes())) }
            readCategories()
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
     * Asks the moderator to confirm retiring [questionId], an approved question [from] shows, the list
     * of every question or the reported ones. Nothing is sent until [confirmRetire].
     */
    override fun askToRetire(
        questionId: String,
        from: Screen,
    ) {
        val current = _state.value
        val shown = current.shownOn(from, questionId) ?: return
        if (!current.canSend || shown.status != SubmissionStatus.APPROVED) return
        _state.update { it.copy(retiring = Retiring(questionId, from)) }
    }

    override fun cancelRetire() = _state.update { it.copy(retiring = null) }

    /** Retires the question [askToRetire] asked about, and lists it as the server answered it. */
    override fun confirmRetire() {
        val current = _state.value
        val (questionId, from) = current.retiring ?: return
        if (!current.canSend) return
        _state.update { it.copy(retiring = null) }
        move(Action.RETIRE, questionId, from, send = { token -> retireQuestion(token, questionId) }) { retired ->
            "Retired ${optionsOf(retired.optionA, retired.optionB)}: served to nobody until restored."
        }
    }

    /** Restores the retired question [questionId], and lists it as the server answered it. */
    override fun restore(
        questionId: String,
        from: Screen,
    ) = move(Action.RESTORE, questionId, from, send = { token -> restoreQuestion(token, questionId) }) { restored ->
        "Restored ${optionsOf(restored.optionA, restored.optionB)}: served again."
    }

    /** Reads the categories, as an action of its own, starting the categories' outcomes afresh. */
    override fun loadCategories() =
        exclusively(Running(Action.LOAD_CATEGORIES)) {
            _state.update { it.withCategories { list -> list.copy(addFailure = null, renameFailure = null) } }
            readCategories()
        }

    override fun editNewCategory(draft: CategoryDraft) =
        _state.update {
            it.withCategories { list -> list.copy(adding = draft) }
        }

    /**
     * Adds the category typed, under its id, or under one the server makes from the English name when
     * none is typed, once [CategoryDraft.isValid]; then reads the categories again, whatever became of
     * it: an add whose answer was lost may have been made, and one refused as existing lists the
     * category that has the id. What was typed is cleared once it is added, unless typed over meanwhile.
     */
    override fun saveNewCategory() {
        val draft = _state.value.categories.adding
        if (!draft.isValid) return
        exclusively(Running(Action.ADD_CATEGORY)) { token ->
            _state.update { it.withCategories { list -> list.copy(addFailure = null, renameFailure = null) } }
            val failure =
                failureOf {
                    val added = addCategory(token, draft.idToSend, draft.nameSr, draft.nameEn)
                    val notice = "Added ${added.id}: ${added.nameSr} / ${added.nameEn}."
                    _state.update {
                        it.noticed(Screen.CATEGORIES, notice).withCategories { list ->
                            list.copy(adding = if (list.adding == draft) CategoryDraft() else list.adding)
                        }
                    }
                }
            if (failure != null) _state.update { it.withCategories { list -> list.copy(addFailure = failure) } }
            readCategories()
        }
    }

    /** Starts putting right the names of the category [categoryId], from the ones it has. */
    override fun startRenaming(categoryId: String) {
        val category =
            _state.value.categories.categories
                ?.firstOrNull { it.id == categoryId } ?: return
        val draft = CategoryDraft(id = category.id, nameSr = category.nameSr, nameEn = category.nameEn)
        _state.update { it.withCategories { list -> list.copy(renaming = draft, renameFailure = null) } }
    }

    override fun editRenaming(draft: CategoryDraft) =
        _state.update {
            it.withCategories { list ->
                val renaming = list.renaming ?: return@withCategories list
                list.copy(renaming = draft.copy(id = renaming.id))
            }
        }

    /** Drops the names typed for the category being renamed; nothing is sent. */
    override fun cancelRenaming() =
        _state.update {
            it.withCategories { list ->
                list.copy(renaming = null, renameFailure = null)
            }
        }

    /**
     * Sends the names typed for the category being renamed, once [CategoryDraft.isValid]; then reads
     * the categories again, whatever became of it, as [saveNewCategory] does.
     */
    override fun saveRenaming() {
        val draft = _state.value.categories.renaming ?: return
        if (!draft.isValid) return
        exclusively(Running(Action.RENAME_CATEGORY)) { token ->
            _state.update { it.withCategories { list -> list.copy(addFailure = null, renameFailure = null) } }
            val failure =
                failureOf {
                    val renamed = renameCategory(token, draft.id, draft.nameSr, draft.nameEn)
                    val notice = "Renamed ${renamed.id}: ${renamed.nameSr} / ${renamed.nameEn}."
                    _state.update {
                        it.noticed(Screen.CATEGORIES, notice).withCategories { list ->
                            list.copy(renaming = list.renaming?.takeUnless { renaming -> renaming == draft })
                        }
                    }
                }
            if (failure != null) _state.update { it.withCategories { list -> list.copy(renameFailure = failure) } }
            readCategories()
        }
    }

    /**
     * Sends a decision on [questionId] from [from], which answers with the line to show once it is
     * made, then reads the queue again, and the list if it was read, whatever became of it: a decision
     * whose answer was lost may still have been made, and one another moderator beat has changed both.
     * Not after a refusal every request would meet ([refusesEveryRequest]), which decided nothing.
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
        if (failure.refusesEveryRequest) return@acting failure
        readQueue(token)
        if (_state.value.questions.questions != null) rereadList(token)
        failure
    }

    /**
     * Retires or restores [questionId] from [from] through [send], which answers with the question as
     * the list now shows it, puts that in the question's row wherever it is shown, the list of every
     * question and the reported ones, and says what was done in the line [noticeOf] makes of it. The
     * server reads the answer as it reads a page's row, in the move's own transaction, so reading
     * again would only spend the admin budget, a request per page shown. A move that failed reads
     * [from] again instead, whatever became of it: a question another moderator moved first answers
     * `WRONG_STATUS`, and the screen then shows where it stands. Not after a refusal every request
     * would meet ([refusesEveryRequest]), which moved nothing.
     */
    private fun move(
        action: Action,
        questionId: String,
        from: Screen,
        send: suspend (AdminToken) -> ModeratedQuestion,
        noticeOf: (ModeratedQuestion) -> String,
    ) = acting(Running(action, questionId), from) { token ->
        val failure =
            failureOf {
                val moved = send(token)
                _state.update { it.noticed(from, noticeOf(moved)).listing(moved).reporting(moved) }
            }
        if (failure == null || failure.refusesEveryRequest) return@acting failure
        when (from) {
            Screen.REPORTS -> {
                readReports(token)
            }

            Screen.QUESTIONS -> {
                rereadList(token)
            }

            Screen.PENDING, Screen.CATEGORIES -> {}
        }
        failure
    }

    /** The reported questions with [question] in its row, as the server answered it, its reports as they were. */
    private fun ModerationState.reporting(question: ModeratedQuestion): ModerationState {
        val listed = reports.reports ?: return this
        val rows = listed.map { row -> if (row.question.id == question.id) row.copy(question = question) else row }
        return copy(reports = reports.copy(reports = rows))
    }

    /**
     * The list with [question] in its row, as the server answered it, or without it once the list's
     * filter no longer picks it (a retired question in a list of approved ones, say), as a read at
     * that filter would list it. A move keeps a question's place, its time and id, in the order.
     */
    private fun ModerationState.listing(question: ModeratedQuestion): ModerationState {
        val listed = questions.questions ?: return this
        val picked = questions.filter.picks(question)
        val rows = listed.mapNotNull { row -> if (row.id != question.id) row else question.takeIf { picked } }
        return copy(questions = questions.copy(questions = rows))
    }

    /** Whether the server lists [question] at this filter: any of its statuses, and any of its categories. */
    private fun QuestionFilter.picks(question: ModeratedQuestion): Boolean =
        (statuses.isEmpty() || question.status in statuses) &&
            (categories.isEmpty() || question.categories.any { it in categories })

    /**
     * Whether this is a refusal the server gave before doing anything, and would give any admin request
     * sent now: a wrong token (403) or the rate limit (429). Reading again after one would change
     * nothing on screen and only spend the address's admin budget, and after a 403 one more of its ten
     * wrong tokens a minute, past which every admin request from it is refused (CLAUDE.md §8b).
     */
    private val Failure?.refusesEveryRequest: Boolean
        get() = this is Failure.Refused && (error == DomainError.FORBIDDEN || error == DomainError.RATE_LIMITED)

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

    /** Reads every category; a read that fails keeps what was listed and says why. */
    private suspend fun readCategories() {
        val failure =
            failureOf {
                val listed = getCategories()
                _state.update { it.withCategories { list -> list.copy(categories = listed, failure = null) } }
            }
        if (failure != null) _state.update { it.copy(categories = it.categories.copy(failure = failure)) }
    }

    /** Reads the reported questions; a read that fails keeps what was listed and says why. */
    private suspend fun readReports(token: AdminToken) {
        val failure =
            failureOf {
                val reported = getReportedQuestions(token)
                _state.update { it.copy(reports = it.reports.copy(reports = reported, failure = null)) }
            }
        if (failure != null) _state.update { it.copy(reports = it.reports.copy(failure = failure)) }
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
        val lockedAtStart = current.locks
        // A notice says what the last action did, so the next one clears it wherever it was shown.
        _state.update {
            Screen.entries
                .fold(
                    it,
                ) { cleared, screen -> cleared.noticed(screen, null) }
                .copy(running = running)
        }

        inFlight =
            viewModelScope.launch {
                try {
                    work(token)
                } finally {
                    // A lock meanwhile has already cleared it, and may have let another action start.
                    if (lockedAtStart == _state.value.locks) _state.update { it.copy(running = null) }
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
        val question = current.shownOn(Screen.QUESTIONS, questionId) ?: current.shownOn(Screen.REPORTS, questionId)
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
            Screen.REPORTS -> copy(reports = reports.copy(outcomes = change(reports.outcomes)))
            Screen.QUESTIONS -> copy(questions = questions.copy(outcomes = change(questions.outcomes)))
            Screen.CATEGORIES -> withCategories { it.copy(outcomes = change(it.outcomes)) }
        }

    private fun ModerationState.withCategories(change: (CategoryList) -> CategoryList): ModerationState =
        copy(categories = change(categories))

    /** Drops what was picked for a question no longer pending on either screen. */
    private fun ModerationState.pruned(): ModerationState {
        val pendingIds =
            pending.submissions
                .orEmpty()
                .map { it.id }
                .toSet() + questions.pendingIds
        return copy(drafts = drafts.filterKeys(pendingIds::contains))
    }

    /** This set with [value] in it, or out of it if it was in, in order: declaration order, or an id's. */
    private fun <T : Comparable<T>> Set<T>.toggled(value: T): Set<T> =
        (if (value in this) this - value else this + value).sorted().toSet()
}
