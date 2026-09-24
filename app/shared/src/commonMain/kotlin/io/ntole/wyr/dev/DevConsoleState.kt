package io.ntole.wyr.dev

import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.session.SessionInfo
import io.ntole.wyr.core.domain.vote.AttemptId
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.VoteOutcome

/**
 * What the dev console shows, apart from the HTTP trace, which it reads from the network layer
 * as it is.
 *
 * [session] and [queueSize] are snapshots, taken when the console opens and after every action.
 * A snapshot that cannot be taken leaves them as they were and adds a `refreshHeader` log entry.
 *
 * [stats] are read from the server when the console opens, after every vote, Skip, Answer N and
 * New guest, and on Read stats. A vote's outcome drops them, so they are never older than
 * [lastOutcome]. A read that fails leaves them as they were, which may be none. Read stats is an
 * action like any other, logged as `readStats` whether it works or not. The other reads are logged
 * only when they fail, as a `refreshStats` entry.
 */
data class DevConsoleState(
    val apiBaseUrl: String,
    val session: SessionInfo? = null,
    val queueSize: Int? = null,
    /**
     * The category the feed is filtered to, `null` for every category. Followed as the repository
     * holds it rather than taken as a snapshot, so it always says what the next fetch asks for.
     */
    val category: Category? = null,
    val question: Question? = null,
    val lastOutcome: VoteOutcome? = null,
    /**
     * The player [lastOutcome] was paid to, as the session stood once it landed. Not always the one
     * it was sent for: a vote refused as a session the server has stopped accepting goes out again
     * as a fresh guest.
     */
    val lastOutcomePlayerId: String? = null,
    val stats: PlayerStats? = null,
    /** The last vote sent, whether or not it got an answer, for Retry last vote to send again. */
    val lastVote: SentVote? = null,
    /** The action in flight, or `null` when idle. Only one runs at a time. */
    val running: String? = null,
    /** Newest first, at most [DevConsoleViewModel.LOG_CAPACITY] entries. */
    val log: List<LogEntry> = emptyList(),
) {
    val isBusy: Boolean get() = running != null

    /**
     * True when [stats] are another player's than [lastOutcome], so the two are not compared. A
     * stats read refused as a session the server has stopped accepting reads a fresh guest's, which
     * say nothing about what the player before was paid.
     */
    val statsForAnotherPlayer: Boolean
        get() {
            val stats = stats ?: return false
            return lastOutcome != null && stats.playerId != lastOutcomePlayerId
        }

    /**
     * True when [stats] and [lastOutcome] disagree on the player's total points. Both are the
     * server's word, and the stats were read after the outcome, so they should agree. When they do
     * not, the server paid for something the console has no outcome for: a vote whose answer was
     * lost, which Retry last vote then replays, or one cast from the Play tab. Otherwise it is a bug.
     * Never true while [statsForAnotherPlayer] is.
     */
    val pointsMismatch: Boolean
        get() {
            val stats = stats ?: return false
            val outcome = lastOutcome ?: return false
            return !statsForAnotherPlayer && stats.totalPoints != outcome.totalPoints
        }
}

/** A vote as sent, with the attempt it went out as. */
data class SentVote(
    val questionId: String,
    val side: Side,
    val attempt: AttemptId,
)

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
