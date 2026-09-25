package io.ntole.wyr.server.auth

import io.ntole.wyr.server.db.Sessions
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/**
 * A player's sessions (CLAUDE.md §8a, *Sessions*): one refresh-token family per device, each rotating
 * on its own.
 */
object SessionStore {
    /** One device's session of a player: what an access token names beside the player. */
    data class Session(
        val id: String,
        val playerId: String,
    )

    /**
     * Opens a session for [playerId] with the refresh token whose hash is [refreshTokenHash], valid
     * until [expiresAt], and returns it. Must run inside a transaction.
     */
    fun open(
        playerId: String,
        refreshTokenHash: String,
        expiresAt: Long,
        now: Long = System.currentTimeMillis(),
    ): Session {
        val session = Session(id = UUID.randomUUID().toString(), playerId = playerId)
        Sessions.insert { row ->
            row[Sessions.id] = session.id
            row[Sessions.playerId] = playerId
            row[Sessions.refreshTokenHash] = refreshTokenHash
            row[Sessions.refreshTokenExpiresAt] = expiresAt
            row[Sessions.createdAt] = now
        }
        return session
    }

    /**
     * Ends [playerId]'s session [sessionId], a logout (CLAUDE.md §8a, *Sessions*): its row is deleted,
     * so neither of its refresh tokens works again. The player's other sessions, on other devices, are
     * left alone. A session that is already gone, or another player's, is left as it is. Must run
     * inside a transaction.
     *
     * The access tokens issued to it still work until each expires: nothing reads a session to let a
     * request in.
     */
    fun close(
        sessionId: String,
        playerId: String,
    ) {
        Sessions.deleteWhere { (Sessions.id eq sessionId) and (Sessions.playerId eq playerId) }
    }

    /**
     * Spends the refresh token whose hash is [presentedHash]: swaps it for [newHash], valid until
     * [expiresAt], in the session it belongs to, and returns that session, or `null` when no refresh may
     * spend it. Must run inside a transaction.
     *
     * A refresh may spend a session's current token until it expires, and the token the session's last
     * rotation displaced (CLAUDE.md §8a) until the next rotation displaces it in turn, never past its
     * own expiry. A [graceMillis] bounds that in time as well, to so long after the rotation that
     * displaced it, and 0 accepts the current token alone; null, the server's default, sets no time
     * bound. Either way the swap is the same: the token current until now becomes the previous one,
     * stamped [now], and [newHash] the current one. So a token works twice at most. Spent while current,
     * it becomes the previous one, which one more refresh may spend until the new token's first use
     * displaces it; spent as the previous one, it is displaced by the token current then, which becomes
     * the previous one in its place, and it never works again. The stamp is written with no bound too: a
     * bound set later reads it. Each session's tokens are its own, so a refresh on one device never
     * touches another's.
     *
     * Returns `null` alike for an unknown, an expired, a spent and a displaced token, and one past a
     * bound — the caller must not be able to tell them apart, and neither should an attacker probing
     * the endpoint.
     *
     * One `UPDATE` both checks and swaps: a compare-and-set whose `WHERE` holds everything the swap
     * relies on, with nothing read before it. At READ COMMITTED a second refresh presenting the same
     * token waits on the first's row lock and then re-checks that `WHERE` against the row the first
     * committed, so it never swaps from the state the first swapped from. Of two presenting the current
     * token, the second finds it the previous one by then and still spends it, so both go through and
     * the first's new token is the previous one; of two presenting the previous token, the second finds
     * it gone and is refused. An update by id after a read, or with the hash in its `WHERE` and not the
     * rest, lets both through from one state: two live sessions from one token, one of them never
     * displaced.
     */
    fun rotate(
        presentedHash: String,
        newHash: String,
        expiresAt: Long,
        graceMillis: Long?,
        now: Long = System.currentTimeMillis(),
    ): Session? {
        val swapped =
            Sessions.update({ spendable(presentedHash, graceMillis, now) }) { row ->
                // The right-hand columns are the row as it stood before this update, on both engines,
                // so whichever token was presented, the one current until now becomes the previous one.
                row[previousRefreshTokenHash] = refreshTokenHash
                row[previousRefreshTokenExpiresAt] = refreshTokenExpiresAt
                row[previousRefreshTokenRotatedAt] = now
                row[refreshTokenHash] = newHash
                row[refreshTokenExpiresAt] = expiresAt
            }
        if (swapped == 0) return null

        return Sessions
            .select(Sessions.id, Sessions.playerId)
            .where { Sessions.refreshTokenHash eq newHash }
            .single()
            .let { row -> Session(id = row[Sessions.id], playerId = row[Sessions.playerId]) }
    }

    /** Whether a refresh may spend [presentedHash] at [now], as [rotate] describes. */
    private fun spendable(
        presentedHash: String,
        graceMillis: Long?,
        now: Long,
    ): Op<Boolean> {
        val asCurrent = (Sessions.refreshTokenHash eq presentedHash) and (Sessions.refreshTokenExpiresAt greater now)
        val asPrevious =
            (Sessions.previousRefreshTokenHash eq presentedHash) and
                (Sessions.previousRefreshTokenExpiresAt greater now)
        return when {
            graceMillis == null -> {
                asCurrent or asPrevious
            }

            graceMillis > 0 -> {
                val displacedWithinBound = Sessions.previousRefreshTokenRotatedAt greater now - graceMillis
                asCurrent or (asPrevious and displacedWithinBound)
            }

            else -> {
                asCurrent
            }
        }
    }
}
