package io.ntole.wyr.submit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ntole.wyr.core.domain.analytics.Analytics
import io.ntole.wyr.core.domain.analytics.AnalyticsEvent
import io.ntole.wyr.core.domain.analytics.AnalyticsProperty
import io.ntole.wyr.core.domain.category.CategoryRepository
import io.ntole.wyr.core.domain.category.GetCategories
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.player.GetPlayerStats
import io.ntole.wyr.core.domain.submission.SubmitQuestion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Submit screen can ask for, so the screen takes one argument for all of it. */
interface SubmitActions {
    /** Reads the categories and the player's points again. */
    fun refresh()

    /** The form went back to My questions for [SubmitState.sent]: takes it down. */
    fun leftForm()

    fun setOptionA(text: String)

    fun setOptionB(text: String)

    /** Picks the category [id] for the question, or unpicks it if it is picked. */
    fun toggleCategory(id: String)

    fun submit()
}

/**
 * Drives the Submit screen's form (CLAUDE.md §8d, *Submitting*): a question written and filed under
 * the categories picked from the server's, read through [GetCategories], sent through
 * [SubmitQuestion], and the player's points read through [GetPlayerStats], since a question costs
 * [io.ntole.wyr.core.domain.submission.SubmissionRules.SUBMISSION_COST].
 *
 * One action at a time, and the points read again after every submit, a failed one too: a submission
 * whose answer was lost may have been stored, and paid for.
 *
 * [analytics] hear of it (CLAUDE.md §8g): the form opened, a question sent and its categories, a
 * refusal by its code, and every failure shown; never what was typed.
 */
class SubmitViewModel(
    private val submitQuestion: SubmitQuestion,
    private val getPlayerStats: GetPlayerStats,
    private val getCategories: GetCategories,
    categoryList: CategoryRepository,
    private val analytics: Analytics,
) : ViewModel(),
    SubmitActions {
    private val _state = MutableStateFlow(SubmitState(categoryOptions = categoryList.categories.value))
    val state: StateFlow<SubmitState> = _state.asStateFlow()

    init {
        // Whoever read them last, this screen or another: the chips are the repository's list.
        viewModelScope.launch {
            categoryList.categories.collect { listed -> _state.update { it.copy(categoryOptions = listed) } }
        }
    }

    /**
     * Not read on creation: the form asks every time it is shown, since the points move meanwhile and a
     * moderator adds categories. The categories first, then the points. Like every action, it takes
     * down a [SubmitState.sent] left from a showing before.
     */
    override fun refresh() = perform(SubmitAction.LOAD) { readCategories() }

    /**
     * The form is shown: the categories and the points are read again, and the analytics hear of it
     * when that begins a [newVisit], not when a rotation's composition shows the same one (CLAUDE.md §8g).
     */
    fun shown(newVisit: Boolean = true) {
        if (newVisit) analytics.track(AnalyticsEvent.SUBMIT_OPENED)
        refresh()
    }

    override fun leftForm() = _state.update { it.copy(sent = false) }

    override fun setOptionA(text: String) = edit { copy(optionA = text) }

    override fun setOptionB(text: String) = edit { copy(optionB = text) }

    override fun toggleCategory(id: String) =
        edit { copy(categories = if (id in categories) categories - id else categories + id) }

    /**
     * Sends the question as typed, then reads the points again. Nothing happens until
     * [SubmitState.canSubmit]. Once the server stores it, the form is cleared for the next one and
     * [SubmitState.sent] raised, for the form to go back to My questions on; a refusal keeps it, to
     * put right and send again.
     */
    override fun submit() {
        val draft = _state.value
        if (!draft.canSubmit) return
        perform(SubmitAction.SUBMIT) {
            try {
                submitQuestion(draft.optionA, draft.optionB, draft.categories)
                analytics.track(
                    AnalyticsEvent.SUBMIT_SENT,
                    mapOf(
                        AnalyticsProperty.CATEGORIES to draft.categories.sorted(),
                        AnalyticsProperty.COUNT to draft.categories.size,
                    ),
                )
                _state.update { it.copy(optionA = "", optionB = "", categories = emptySet(), sent = true) }
            } catch (failure: WyrException) {
                analytics.track(AnalyticsEvent.SUBMIT_REFUSED, mapOf(AnalyticsProperty.CODE to failure.error.name))
                reportShown(failure.error, ACTION_SUBMIT)
                _state.update { it.copy(submitFailure = failure.toSubmitFailure()) }
            }
        }
    }

    /** Ignored while a question is being sent, so what is cleared once it is stored is what was sent. */
    private fun edit(change: SubmitState.() -> SubmitState) = _state.update { if (it.isSubmitting) it else it.change() }

    /**
     * Runs [block] as the one action in flight, then reads the points again, whatever [block] ended
     * in: it records its own failure. A second action while one runs is ignored.
     */
    private fun perform(
        action: SubmitAction,
        block: suspend () -> Unit,
    ) {
        if (_state.value.isBusy) return
        _state.update { it.copy(running = action, submitFailure = null, pointsFailure = null, sent = false) }

        viewModelScope.launch {
            try {
                block()
                load()
            } finally {
                _state.update { it.copy(running = null) }
            }
        }
    }

    /** Reads every category again; a read that fails keeps those read before and says so under them. */
    private suspend fun readCategories() {
        val failure =
            try {
                getCategories()
                null
            } catch (unread: WyrException) {
                reportShown(unread.error, ACTION_CATEGORIES)
                unread.toSubmitFailure()
            }
        _state.update { it.copy(categoriesFailure = failure) }
    }

    /**
     * Reads the player's points, and whether they are registered, minting a guest where there is none.
     * A failed read keeps what was shown and says so under Send, whatever the action before it ended in,
     * so points never read are never left waiting on nothing.
     */
    private suspend fun load() {
        try {
            val stats = getPlayerStats()
            _state.update { it.copy(points = stats.totalPoints, registered = stats.username != null) }
        } catch (failure: WyrException) {
            reportShown(failure.error, ACTION_POINTS)
            _state.update { it.copy(pointsFailure = failure.toSubmitFailure()) }
        }
    }

    private fun reportShown(
        error: DomainError,
        action: String,
    ) {
        analytics.track(
            AnalyticsEvent.ERROR_SHOWN,
            mapOf(AnalyticsProperty.CODE to error.name, AnalyticsProperty.ACTION to action),
        )
    }
}

private fun WyrException.toSubmitFailure(): SubmitFailure = SubmitFailure(error, retryAfter)

private const val ACTION_SUBMIT = "submit"
private const val ACTION_CATEGORIES = "categories"
private const val ACTION_POINTS = "points"
