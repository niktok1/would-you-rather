package io.ntole.wyr.submit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ntole.wyr.core.domain.category.CategoryRepository
import io.ntole.wyr.core.domain.category.GetCategories
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.submission.GetMySubmissions
import io.ntole.wyr.core.domain.submission.SubmitQuestion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Submit screen can ask for, so the screen takes one argument for all of it. */
interface SubmitActions {
    /** Reads the categories and the player's own submissions again. */
    fun refresh()

    fun setOptionA(text: String)

    fun setOptionB(text: String)

    /** Picks the category [id] for the question, or unpicks it if it is picked. */
    fun toggleCategory(id: String)

    fun submit()
}

/**
 * Drives the Submit screen (CLAUDE.md §8d, *Submitting*): a question written and filed under the
 * categories picked from the server's, read through [GetCategories], sent through [SubmitQuestion],
 * and the player's own submissions read through [GetMySubmissions].
 *
 * One action at a time, and the list read again after every submit, a failed one too: a submission
 * whose answer was lost may have been stored, and the list is where it shows.
 */
class SubmitViewModel(
    private val submitQuestion: SubmitQuestion,
    private val getMySubmissions: GetMySubmissions,
    private val getCategories: GetCategories,
    categoryList: CategoryRepository,
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
     * Not read on creation: the screen asks every time it is shown, since a moderator decides
     * meanwhile, and adds categories. The categories first, then the list.
     */
    override fun refresh() = perform(SubmitAction.LOAD) { readCategories() }

    override fun setOptionA(text: String) = edit { copy(optionA = text) }

    override fun setOptionB(text: String) = edit { copy(optionB = text) }

    override fun toggleCategory(id: String) =
        edit { copy(categories = if (id in categories) categories - id else categories + id) }

    /**
     * Sends the question as typed, then reads the list again. Nothing happens until
     * [SubmitState.canSubmit]. Once the server stores it, the form is cleared for the next one; a
     * refusal keeps it, to put right and send again.
     */
    override fun submit() {
        val draft = _state.value
        if (!draft.canSubmit) return
        perform(SubmitAction.SUBMIT) {
            try {
                submitQuestion(draft.optionA, draft.optionB, draft.categories)
                _state.update { it.copy(optionA = "", optionB = "", categories = emptySet(), sent = true) }
            } catch (failure: WyrException) {
                _state.update { it.copy(submitFailure = failure.toSubmitFailure()) }
            }
        }
    }

    /** Ignored while a question is being sent, so what is cleared once it is stored is what was sent. */
    private fun edit(change: SubmitState.() -> SubmitState) = _state.update { if (it.isSubmitting) it else it.change() }

    /**
     * Runs [block] as the one action in flight, then reads the list again, whatever [block] ended in:
     * it records its own failure. A second action while one runs is ignored.
     */
    private fun perform(
        action: SubmitAction,
        block: suspend () -> Unit,
    ) {
        if (_state.value.isBusy) return
        _state.update { it.copy(running = action, submitFailure = null, listFailure = null, sent = false) }

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
                unread.toSubmitFailure()
            }
        _state.update { it.copy(categoriesFailure = failure) }
    }

    /**
     * Reads the player's submissions, minting a guest where there is none. A failed read keeps what
     * was shown and says so under the list, whatever the action before it ended in, so a list never
     * read is never left waiting on nothing.
     */
    private suspend fun load() {
        try {
            val submissions = getMySubmissions()
            _state.update { it.copy(submissions = submissions) }
        } catch (failure: WyrException) {
            _state.update { it.copy(listFailure = failure.toSubmitFailure()) }
        }
    }
}

private fun WyrException.toSubmitFailure(): SubmitFailure = SubmitFailure(error, retryAfter)
