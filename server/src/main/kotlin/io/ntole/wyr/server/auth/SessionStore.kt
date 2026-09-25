package io.ntole.wyr.server.auth

import io.ntole.wyr.server.db.Players
import io.ntole.wyr.server.db.Sessions
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isDistinctFrom
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.stringParam
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/**
 * A player's sessions (CLAUDE.md §8a, *Sessions*): one refresh-token family per device, each rotating
 * on its own, and the mirror, the copy of the session written last that a build from before sessions
 * refreshes from ([Players.refreshTokenHash]).
 */
object SessionStore {
    /**
     * Opens a session for [playerId] with the refresh token whose hash is [refreshTokenHash], valid
     * until [expiresAt], and mirrors it: the session written last is the one a rollback keeps. Must run
     * inside a transaction.
     */
    fun open(
        playerId: String,
        refreshTokenHash: String,
        expiresAt: Long,
        now: Long = System.currentTimeMillis(),
    ) {
        Sessions.insert { row ->
            row[Sessions.id] = UUID.randomUUID().toString()
            row[Sessions.playerId] = playerId
            row[Sessions.refreshTokenHash] = refreshTokenHash
            row[Sessions.refreshTokenExpiresAt] = expiresAt
            row[Sessions.createdAt] = now
        }
        mirror(
            Tokens(
                playerId = playerId,
                hash = refreshTokenHash,
                expiresAt = expiresAt,
                previousHash = null,
                previousExpiresAt = null,
                rotatedAt = null,
            ),
        )
    }

    /**
     * Opens a session for the player whose recovery secret's hash is [secretHash] (CLAUDE.md §8a,
     * *Recovery*), as [open] does, and returns that player, or `null` when no player holds that secret
     * now. Must run inside a transaction.
     *
     * The secret is not spent: it opens another session each time it is presented, until its player
     * replaces it (`PlayerStore.replaceRecoverySecret`), since the copy a restored phone has may lag the
     * last one its player was given. It is looked up by its hash, through the unique index, as a refresh
     * token is: only hashes are ever compared, and a hash, even one a lookup's timing gave away, does not
     * give its secret away.
     *
     * The player's id is copied from a plain read, the §4 exception: a replacement of the secret that
     * commits between that read and the session's insert orders the recovery before itself, which it
     * does not undo, since a new secret closes none of the sessions the old one opened. So the stale
     * read is harmless, and the player's row is locked only by the mirror's write, after the insert.
     */
    fun recover(
        secretHash: String,
        refreshTokenHash: String,
        expiresAt: Long,
        now: Long = System.currentTimeMillis(),
    ): String? {
        val playerId =
            Players
                .select(Players.id)
                .where { Players.recoverySecretHash eq secretHash }
                .firstOrNull()
                ?.get(Players.id) ?: return null
        open(playerId, refreshTokenHash, expiresAt, now)
        return playerId
    }

    /**
     * Spends the refresh token whose hash is [presentedHash]: swaps it for [newHash], valid until
     * [expiresAt], in the session it belongs to, and returns that session's player, or `null` when no
     * refresh may spend it. Must run inside a transaction.
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
     * bound set later reads it, and so does a rollback to a build that bounds the grace by default. Each
     * session's tokens are its own, so a refresh on one device never touches another's.
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
     *
     * The session it rotates is then mirrored, under the player's row lock, which it takes after the
     * session's: every write here that locks a session it did not insert itself locks it before its
     * player, so no two wait on each other in turn.
     *
     * A token no session holds may still be the mirror's, where a build without sessions rotated it or
     * minted its player ([Players.mirroredRefreshTokenHash]): during a rollback, or while the build
     * before still serves as a deploy's new instance starts. It is spent there by the same rule and
     * folded back into a session ([foldMirror]), so the player keeps it across the roll-forward too.
     * And a token that became a session's while this refresh looked, because a refresh racing it
     * folded it first, is spent there after all, as two refreshes racing with one session's token are.
     *
     * The session the mirror copied is not spent while such a build has moved the mirror on from it
     * ([notMovedOnWithoutSessions]): the device went on with the mirror's tokens, and by that build's
     * rules the session's are ones it displaced, dead once it rotated twice. Spent in the session, a
     * stale copy of one would work again, and the mirror written from it would refuse the device's own.
     * Where that build rotated only once, the session's current token is the mirror's previous one,
     * and the fold spends it there, as that build would have.
     */
    fun rotate(
        presentedHash: String,
        newHash: String,
        expiresAt: Long,
        graceMillis: Long?,
        now: Long = System.currentTimeMillis(),
    ): String? =
        rotateSession(presentedHash, newHash, expiresAt, graceMillis, now)
            ?: foldMirror(presentedHash, newHash, expiresAt, graceMillis, now)
            ?: rotateSession(presentedHash, newHash, expiresAt, graceMillis, now)

    private fun rotateSession(
        presentedHash: String,
        newHash: String,
        expiresAt: Long,
        graceMillis: Long?,
        now: Long,
    ): String? {
        val spendable = sessionColumns.spendable(presentedHash, graceMillis, now) and notMovedOnWithoutSessions()
        val swapped =
            Sessions.update({ spendable }) { row ->
                // The right-hand columns are the row as it stood before this update, on both engines,
                // so whichever token was presented, the one current until now becomes the previous one.
                row[previousRefreshTokenHash] = refreshTokenHash
                row[previousRefreshTokenExpiresAt] = refreshTokenExpiresAt
                row[previousRefreshTokenRotatedAt] = now
                row[refreshTokenHash] = newHash
                row[refreshTokenExpiresAt] = expiresAt
            }
        if (swapped == 0) return null

        val session =
            Sessions
                .selectAll()
                .where { Sessions.refreshTokenHash eq newHash }
                .single()
                .sessionTokens()
        mirror(session)
        return session.playerId
    }

    /**
     * Whether the session being updated is not the one a build without sessions moved its player's
     * mirror on from: the session whose current token is the mark, while the mirror's current token is
     * no longer it. Read in the rotation's own `UPDATE`, by the player's key.
     */
    private fun notMovedOnWithoutSessions(): Op<Boolean> =
        notExists(
            Players
                .select(Players.id)
                .where {
                    (Players.id eq Sessions.playerId) and
                        (Players.mirroredRefreshTokenHash eq Sessions.refreshTokenHash) and
                        (Players.refreshTokenHash isDistinctFrom Players.mirroredRefreshTokenHash)
                },
        )

    /**
     * Spends [presentedHash] in the mirror of a player whose mirror a build without sessions has moved,
     * by the rule a session's token is spent by, and folds the result into the session the mirror
     * copied, or into a new one when a build without sessions minted the player, so it lives on as a
     * session. Returns the player, or `null` when no such mirror may spend the token.
     *
     * The session the mirror copied is the one whose current hash is [Players.mirroredRefreshTokenHash]:
     * a session's rotation rewrites that mark, so none has rotated since, and it is the device whose
     * refreshes the build without sessions went on with. Folding into it keeps one family per device,
     * rather than leaving its tokens live beside the mirror's.
     *
     * The mark is read first, to find that session and lock it before the player, as every rotation
     * locks them; the player's update is then a compare-and-set on the mark read. A write to the mirror
     * committed in between, a racing fold's or a session's rotation, moves the mark, so the update finds
     * nothing and the token is refused, or spent after all as the session's a racing fold made it
     * ([rotate]).
     */
    private fun foldMirror(
        presentedHash: String,
        newHash: String,
        expiresAt: Long,
        graceMillis: Long?,
        now: Long,
    ): String? {
        val spendable = mirrorColumns.spendable(presentedHash, graceMillis, now)
        val movedWithoutSessions = Players.mirroredRefreshTokenHash isDistinctFrom Players.refreshTokenHash
        val found =
            Players
                .select(Players.id, Players.mirroredRefreshTokenHash)
                .where { spendable and movedWithoutSessions }
                .firstOrNull() ?: return null
        val playerId = found[Players.id]
        val mark = found[Players.mirroredRefreshTokenHash]

        val copied =
            mark?.let { hash ->
                Sessions
                    .select(Sessions.id)
                    .where { Sessions.refreshTokenHash eq hash }
                    .forUpdate()
                    .firstOrNull()
                    ?.get(Sessions.id)
            }

        // The mark as read. That keeps the mirror moved too: its token never goes back to the one the
        // mark names.
        val markUnmoved =
            if (mark == null) Players.mirroredRefreshTokenHash.isNull() else Players.mirroredRefreshTokenHash eq mark
        val swapped =
            Players.update({ (Players.id eq playerId) and spendable and markUnmoved }) { row ->
                row[previousRefreshTokenHash] = refreshTokenHash
                row[previousRefreshTokenExpiresAt] = refreshTokenExpiresAt
                row[previousRefreshTokenRotatedAt] = now
                row[refreshTokenHash] = newHash
                row[refreshTokenExpiresAt] = expiresAt
                row[mirroredRefreshTokenHash] = newHash
            }
        if (swapped == 0) return null

        val folded =
            Players
                .selectAll()
                .where { Players.id eq playerId }
                .single()
                .mirrorTokens()
        if (copied == null) {
            Sessions.insert { row ->
                row[id] = UUID.randomUUID().toString()
                row[Sessions.playerId] = playerId
                row[createdAt] = now
                row.write(folded)
            }
        } else {
            Sessions.update({ Sessions.id eq copied }) { row -> row.write(folded) }
        }
        return playerId
    }

    /** Makes the mirror of [session]'s player a copy of [session]'s tokens, marked as this build's copy. */
    private fun mirror(session: Tokens) {
        val updated =
            Players.update({ Players.id eq session.playerId }) { row ->
                row[refreshTokenHash] = session.hash
                row[refreshTokenExpiresAt] = session.expiresAt
                row[previousRefreshTokenHash] = session.previousHash
                row[previousRefreshTokenExpiresAt] = session.previousExpiresAt
                row[previousRefreshTokenRotatedAt] = session.rotatedAt
                row[mirroredRefreshTokenHash] = session.hash
            }
        check(updated == 1) { "player ${session.playerId} vanished mid-transaction" }
    }

    /** A family's tokens, as a session holds them and as the mirror copies them. */
    private data class Tokens(
        val playerId: String,
        val hash: String,
        val expiresAt: Long,
        val previousHash: String?,
        val previousExpiresAt: Long?,
        val rotatedAt: Long?,
    )

    private fun ResultRow.sessionTokens(): Tokens =
        Tokens(
            playerId = this[Sessions.playerId],
            hash = this[Sessions.refreshTokenHash],
            expiresAt = this[Sessions.refreshTokenExpiresAt],
            previousHash = this[Sessions.previousRefreshTokenHash],
            previousExpiresAt = this[Sessions.previousRefreshTokenExpiresAt],
            rotatedAt = this[Sessions.previousRefreshTokenRotatedAt],
        )

    /** The mirror's tokens, from a row [foldMirror] has just rotated, which therefore holds a current one. */
    private fun ResultRow.mirrorTokens(): Tokens =
        Tokens(
            playerId = this[Players.id],
            hash = checkNotNull(this[Players.refreshTokenHash]) { "a rotated mirror holds a token" },
            expiresAt = checkNotNull(this[Players.refreshTokenExpiresAt]) { "a rotated mirror holds an expiry" },
            previousHash = this[Players.previousRefreshTokenHash],
            previousExpiresAt = this[Players.previousRefreshTokenExpiresAt],
            rotatedAt = this[Players.previousRefreshTokenRotatedAt],
        )

    private fun UpdateBuilder<*>.write(tokens: Tokens) {
        this[Sessions.refreshTokenHash] = tokens.hash
        this[Sessions.refreshTokenExpiresAt] = tokens.expiresAt
        this[Sessions.previousRefreshTokenHash] = tokens.previousHash
        this[Sessions.previousRefreshTokenExpiresAt] = tokens.previousExpiresAt
        this[Sessions.previousRefreshTokenRotatedAt] = tokens.rotatedAt
    }

    /**
     * A table's refresh-token columns, a session's or the mirror's, and [spendable], the one rule a
     * refresh spends a token of either by.
     */
    private class TokenColumns<H : String?, E : Long?>(
        val hash: Column<H>,
        val expiresAt: Column<E>,
        val previousHash: Column<String?>,
        val previousExpiresAt: Column<Long?>,
        val rotatedAt: Column<Long?>,
    ) {
        /** Whether a refresh may spend [presentedHash] here at [now], as [rotate] describes. */
        fun spendable(
            presentedHash: String,
            graceMillis: Long?,
            now: Long,
        ): Op<Boolean> {
            val asCurrent = (hash eq stringParam(presentedHash)) and (expiresAt greater now)
            val asPrevious = (previousHash eq presentedHash) and (previousExpiresAt greater now)
            return when {
                graceMillis == null -> asCurrent or asPrevious
                graceMillis > 0 -> asCurrent or (asPrevious and (rotatedAt greater now - graceMillis))
                else -> asCurrent
            }
        }
    }

    private val sessionColumns =
        TokenColumns(
            hash = Sessions.refreshTokenHash,
            expiresAt = Sessions.refreshTokenExpiresAt,
            previousHash = Sessions.previousRefreshTokenHash,
            previousExpiresAt = Sessions.previousRefreshTokenExpiresAt,
            rotatedAt = Sessions.previousRefreshTokenRotatedAt,
        )

    private val mirrorColumns =
        TokenColumns(
            hash = Players.refreshTokenHash,
            expiresAt = Players.refreshTokenExpiresAt,
            previousHash = Players.previousRefreshTokenHash,
            previousExpiresAt = Players.previousRefreshTokenExpiresAt,
            rotatedAt = Players.previousRefreshTokenRotatedAt,
        )
}
