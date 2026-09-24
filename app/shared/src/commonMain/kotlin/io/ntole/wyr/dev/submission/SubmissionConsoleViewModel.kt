package io.ntole.wyr.dev.submission

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.session.SessionRepository
import io.ntole.wyr.core.domain.submission.GetMySubmissions
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.core.domain.submission.SubmitQuestion
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
 * Drives the console's *Submit a question* section (CLAUDE.md §8d, *Submitting*): a question typed
 * and filed under the categories picked, sent through [SubmitQuestion], and the author's own
 * submissions read through [GetMySubmissions].
 *
 * A ViewModel of its own beside `DevConsoleViewModel`, with its own log and its own one action at a
 * time, so the section stays in files of its own. The HTTP trace is the console's, since every
 * request goes through the one client.
 */
class SubmissionConsoleViewModel(
    private val submitQuestion: SubmitQuestion,
    private val getMySubmissions: GetMySubmissions,
    private val sessions: SessionRepository,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : ViewModel() {
    private val _state = MutableStateFlow(SubmissionConsoleState())
    val state: StateFlow<SubmissionConsoleState> = _state.asStateFlow()

    init {
        // One action like any other, so nothing is submitted while the first read is in flight. The
        // read ensures a session, so the section can mint one when it opens, as the console does.
        exclusively("refreshSubmissions") { refreshSubmissions() }
    }

    fun setOptionA(text: String) = _state.update { it.copy(optionA = text) }

    fun setOptionB(text: String) = _state.update { it.copy(optionB = text) }

    /** Picks [category] for the question, or unpicks it if it is picked. */
    fun toggleCategory(category: Category) =
        _state.update {
            val toggled = if (category in it.categories) it.categories - category else it.categories + category
            // In declaration order, so the chips, the log and the request name them alike.
            it.copy(categories = toggled.sorted().toSet())
        }

    /**
     * Submits the options as typed, under the categories picked, then reads the list again. Nothing
     * happens until [SubmissionConsoleState.canSubmit].
     *
     * Once the server stores the question, the options are cleared, unless they were changed while it
     * was in flight: submitting the same text twice stores it twice (CLAUDE.md §8b, *Retrying a
     * submission*). The categories stay picked. A refusal keeps everything, so a question the server's
     * rules refused (`INVALID_SUBMISSION`, its message in the log) can be put right and sent again.
     * The list is read again after a failure too: a submission whose answer was lost may have been
     * stored, and the list is where it shows.
     */
    fun submit() {
        val draft = _state.value
        if (!draft.canSubmit) return
        perform("submit", args = draft.args(), readsList = true) {
            val stored = submitQuestion(draft.optionA, draft.optionB, draft.categories)
            _state.update {
                it.copy(
                    optionA = if (it.optionA == draft.optionA) "" else it.optionA,
                    optionB = if (it.optionB == draft.optionB) "" else it.optionB,
                )
            }
            stored.summary()
        }
    }

    /** The list again, as an action of its own. */
    fun listSubmissions() = perform("listSubmissions") { loadSubmissions().summary() }

    private suspend fun loadSubmissions(): List<Submission> {
        val submissions = getMySubmissions()
        // After the read, not before: a read refused as a session the server has stopped accepting
        // went out again as a fresh guest, and the list is theirs. A New guest racing the read makes
        // this wrong (SubmissionConsoleState.listedFor).
        val listedFor = sessions.currentPlayerId()
        _state.update { it.copy(submissions = submissions, listedFor = listedFor) }
        return submissions
    }

    /**
     * Runs [block] as the one action in flight and logs how it ended. [readsList] reads the list
     * again afterwards, for an action that can change it.
     */
    private fun perform(
        action: String,
        args: String = "",
        readsList: Boolean = false,
        block: suspend () -> String,
    ) = exclusively(action) {
        val started = timeSource.markNow()
        val result = resultOf(block)
        log(LogEntry(action, args, started.elapsedNow().inWholeMilliseconds, result))
        // After a failure too: a submission whose answer was lost may still have been stored.
        if (readsList) refreshSubmissions()
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
     * Best effort: a failure keeps the list shown, if any, and is logged as an entry of its own
     * instead of as the action's result.
     */
    private suspend fun refreshSubmissions() {
        val started = timeSource.markNow()
        val result =
            resultOf {
                loadSubmissions()
                "refreshed"
            }
        if (result !is LogResult.Ok) {
            log(LogEntry("refreshSubmissions", "", started.elapsedNow().inWholeMilliseconds, result))
        }
    }

    /** Quoted, so the whitespace the server trims shows in the log. */
    private fun SubmissionConsoleState.args(): String =
        "optionA=\"$optionA\" optionB=\"$optionB\" categories=${categories.logName()}"

    /** The submission as the server stored it, options trimmed and categories filed. */
    private fun Submission.summary(): String =
        "submission=$id status=$status categories=${categories.logName()} A=\"$optionA\" B=\"$optionB\""

    private fun List<Submission>.summary(): String =
        "submissions=$size pending=${count { it.status == SubmissionStatus.PENDING }}"

    private fun Set<Category>.logName(): String = joinToString(",") { it.name }

    private fun log(entry: LogEntry) {
        _state.update { it.copy(log = (listOf(entry) + it.log).take(LOG_CAPACITY)) }
    }

    companion object {
        const val LOG_CAPACITY: Int = 50
    }
}
