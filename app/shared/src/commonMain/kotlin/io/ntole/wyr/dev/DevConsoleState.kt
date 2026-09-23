package io.ntole.wyr.dev

import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.session.SessionInfo
import io.ntole.wyr.core.domain.vote.VoteOutcome

/**
 * What the dev console shows, apart from the HTTP trace, which it reads from the network layer
 * as it is.
 *
 * [session] and [queueSize] are snapshots, taken when the console opens and after every action.
 */
data class DevConsoleState(
    val apiBaseUrl: String,
    val session: SessionInfo? = null,
    val queueSize: Int? = null,
    val question: Question? = null,
    val lastOutcome: VoteOutcome? = null,
    /** The action in flight, or `null` when idle. Only one runs at a time. */
    val running: String? = null,
    /** Newest first, at most [DevConsoleViewModel.LOG_CAPACITY] entries. */
    val log: List<LogEntry> = emptyList(),
) {
    val isBusy: Boolean get() = running != null
}

data class LogEntry(
    val action: String,
    val args: String,
    val elapsedMillis: Long,
    val result: LogResult,
)

sealed interface LogResult {
    data class Ok(
        val summary: String,
    ) : LogResult

    /**
     * A failure the data layer classified. [message] is the exception's diagnostic text, which is
     * never shown to a player; this console is the one screen allowed to show it.
     */
    data class Err(
        val error: DomainError,
        val message: String?,
    ) : LogResult

    /** Anything else that was thrown, which means a bug. */
    data class Crash(
        val type: String,
        val message: String?,
    ) : LogResult
}
