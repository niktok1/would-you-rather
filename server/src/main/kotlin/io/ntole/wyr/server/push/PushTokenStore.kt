package io.ntole.wyr.server.push

import io.ntole.wyr.core.push.PushPlatform
import io.ntole.wyr.server.db.PushTokens
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Sessions
import io.ntole.wyr.server.plugins.ApiFailure
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

/** The devices each player's pushes reach (CLAUDE.md §8a, *Push tokens*). */
object PushTokenStore {
    /** The most devices one player's pushes reach: their newest registrations, so a decision sends a bounded few. */
    const val MAX_TOKENS_PER_PLAYER: Int = 10

    /** Who a question's pushes go to: its author, and the tokens of every device they registered. */
    data class Recipients(
        val playerId: String,
        val tokens: List<String>,
    )

    /**
     * Registers [token] for [playerId]'s device, under its session [sessionId], and keeps only the
     * player's [MAX_TOKENS_PER_PLAYER] newest. Must run inside a transaction.
     *
     * One row per token, since a token is one device's: registered again, by this player or another, it
     * moves to whoever sent it last, the latest owner winning. Moving it is an update by the key, and a
     * token no row has yet is inserted. Two first registrations of one token racing both update nothing
     * and both insert, and the primary key refuses the second once the first commits (CLAUDE.md §4). That
     * is never caught: Exposed reruns the transaction, which finds the row and moves it.
     *
     * A session already ended, whose access token has not expired yet, is [ApiFailure.unauthorized]:
     * its logout took its tokens, and one kept under it now would outlive it. The read before the write
     * only spares a write sure to fail. A logout committing between the two makes the write fail on the
     * foreign key instead, and the rerun finds the session gone; one committing after it deletes this
     * token with the session, by the cascade.
     *
     * The pruning reads and deletes with no lock, so two registrations of one player's racing can leave
     * one token over the bound until the next: harmless, as the bound only keeps a decision's sends few.
     */
    fun register(
        token: String,
        playerId: String,
        sessionId: String,
        platform: PushPlatform,
        now: Long = System.currentTimeMillis(),
    ) {
        val open =
            Sessions
                .select(Sessions.id)
                .where { (Sessions.id eq sessionId) and (Sessions.playerId eq playerId) }
                .limit(1)
                .any()
        if (!open) throw ApiFailure.unauthorized("session ended")

        val moved =
            PushTokens.update({ PushTokens.token eq token }) { row ->
                row[PushTokens.playerId] = playerId
                row[PushTokens.sessionId] = sessionId
                row[PushTokens.platform] = platform
                row[updatedAt] = now
            }
        if (moved == 0) {
            PushTokens.insert { row ->
                row[PushTokens.token] = token
                row[PushTokens.playerId] = playerId
                row[PushTokens.sessionId] = sessionId
                row[PushTokens.platform] = platform
                row[updatedAt] = now
            }
        }

        val stale =
            PushTokens
                .select(PushTokens.token)
                .where { PushTokens.playerId eq playerId }
                .orderBy(PushTokens.updatedAt to SortOrder.DESC, PushTokens.token to SortOrder.ASC)
                .map { row -> row[PushTokens.token] }
                .drop(MAX_TOKENS_PER_PLAYER)
        if (stale.isNotEmpty()) PushTokens.deleteWhere { PushTokens.token inList stale }
    }

    /**
     * Removes [token] if it is [playerId]'s, so their pushes no longer reach that device. One another
     * player registered since is theirs now and stays. Must run inside a transaction.
     */
    fun remove(
        token: String,
        playerId: String,
    ) {
        PushTokens.deleteWhere { (PushTokens.token eq token) and (PushTokens.playerId eq playerId) }
    }

    /**
     * The author of the question [questionId] and their tokens, or null for a question with no author,
     * a seed, or none at all. Must run inside a transaction.
     */
    fun recipientsOf(questionId: String): Recipients? {
        val author =
            Questions
                .select(Questions.authorPlayerId)
                .where { Questions.id eq questionId }
                .singleOrNull()
                ?.get(Questions.authorPlayerId)
                ?: return null
        val tokens =
            PushTokens
                .select(PushTokens.token)
                .where { PushTokens.playerId eq author }
                .orderBy(PushTokens.updatedAt to SortOrder.DESC, PushTokens.token to SortOrder.ASC)
                .map { row -> row[PushTokens.token] }
        return Recipients(author, tokens)
    }

    /**
     * Deletes [token], whoever holds it: Firebase says no device has it any more, so no push can reach
     * one through it. Must run inside a transaction.
     */
    fun forget(token: String) {
        PushTokens.deleteWhere { PushTokens.token eq token }
    }
}
