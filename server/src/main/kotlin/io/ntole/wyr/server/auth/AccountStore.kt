package io.ntole.wyr.server.auth

import io.ntole.wyr.server.db.Players
import io.ntole.wyr.server.plugins.ApiFailure
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

/** Accounts (CLAUDE.md §8b, *Accounts*): a player's username and the hash of their password. */
object AccountStore {
    /**
     * Gives [playerId], a guest, the account [username], lower-cased already ([checkedUsername]), with
     * the password [passwordHash] is the hash of ([Passwords]). Everything else the player has stays as
     * it is. Must run inside a transaction.
     *
     * An unknown player is [ApiFailure.unauthorized], as for a vote: a signed token can outlive its
     * player. One with an account already is [ApiFailure.alreadyRegistered], and a username another
     * player has is [ApiFailure.usernameTaken]; neither writes anything.
     *
     * The reads before the write only spare a write sure to fail (CLAUDE.md §4). What decides a race is
     * the write. Naming the player is a compare-and-set on their having no username, so of two
     * registrations of one player racing, the second waits on the first's row lock, then finds the name
     * set and is [ApiFailure.alreadyRegistered]. Two players racing for one name both find it free, and
     * the unique constraint refuses the second's write once the first commits (SQLState 23505). That is
     * never caught: Exposed rolls back and reruns the whole transaction, which finds the name taken.
     */
    fun register(
        playerId: String,
        username: String,
        passwordHash: String,
    ) {
        val player =
            Players
                .select(Players.username)
                .where { Players.id eq playerId }
                .singleOrNull()
                ?: throw ApiFailure.unauthorized("unknown player")
        if (player[Players.username] != null) throw ApiFailure.alreadyRegistered()

        val taken =
            Players
                .select(Players.id)
                .where { Players.username eq username }
                .limit(1)
                .any()
        if (taken) throw ApiFailure.usernameTaken()

        val named =
            Players.update({ (Players.id eq playerId) and Players.username.isNull() }) { row ->
                row[Players.username] = username
                row[Players.passwordHash] = passwordHash
            }
        if (named == 0) throw ApiFailure.alreadyRegistered()
    }
}
