package io.ntole.wyr.server.auth

import io.ntole.wyr.server.db.Identities
import io.ntole.wyr.server.player.PlayerStore
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select

/** A service whose players can be linked to players here, and sign in as them (CLAUDE.md §8a). */
enum class IdentityProvider {
    /** Google Play Games Services v2, on Android (CLAUDE.md §8a, *Play Games sign-in*). */
    PLAY_GAMES,
}

/**
 * The players of other services linked to players here (CLAUDE.md §8a, *Play Games sign-in*): what
 * makes a sign-in with one no-click, since the service vouches for its player, and a registration, as
 * a username is.
 */
object IdentityStore {
    /**
     * The player [provider]'s player [subject] signs in as, linking it first when it is linked to
     * none. Must run inside a transaction.
     *
     * A subject linked already signs in as the player it is linked to, whoever [callerId] is: a guest
     * meeting it switches to it, as a login does. One linked to nobody is linked to [callerId], the
     * player the request's bearer token names, who keeps everything they have, unless that player is
     * linked to another of [provider]'s players already, or there is no caller, or the token outlived its
     * player: then to a new player, minted for it as a guest is. Either way it never fails once Google
     * has vouched for the subject, whose code a sign-in has spent by then.
     *
     * The reads only spare a write sure to fail (CLAUDE.md §4). What decides a race is the write, and the
     * two constraints on it. Two sign-ins racing to link one subject both find it linked to nobody, and
     * the primary key refuses the second's link once the first commits; two linking one caller to two
     * subjects both find the caller unlinked, and the unique index refuses the second's. Neither refusal
     * is caught: Exposed reruns the transaction, which finds the subject linked and signs in as its
     * player, or the caller linked and mints a player for the second subject. The player a refused
     * attempt minted is rolled back with it.
     */
    fun signIn(
        provider: IdentityProvider,
        subject: String,
        callerId: String?,
        now: Long = System.currentTimeMillis(),
    ): String {
        linkedPlayerOf(provider, subject)?.let { linked -> return linked }

        val caller = callerId?.takeIf { id -> PlayerStore.find(id) != null }
        val owner = caller?.takeIf { id -> !isLinked(id, provider) } ?: PlayerStore.createGuest().id
        Identities.insert { row ->
            row[Identities.provider] = provider
            row[Identities.subject] = subject
            row[playerId] = owner
            row[createdAt] = now
        }
        return owner
    }

    /** Whether [playerId] is linked to any service's player, which registers them as a username does. Must run inside a transaction. */
    fun isLinked(playerId: String): Boolean =
        Identities
            .select(Identities.playerId)
            .where { Identities.playerId eq playerId }
            .limit(1)
            .any()

    private fun isLinked(
        playerId: String,
        provider: IdentityProvider,
    ): Boolean =
        Identities
            .select(Identities.playerId)
            .where { (Identities.playerId eq playerId) and (Identities.provider eq provider) }
            .limit(1)
            .any()

    private fun linkedPlayerOf(
        provider: IdentityProvider,
        subject: String,
    ): String? =
        Identities
            .select(Identities.playerId)
            .where { (Identities.provider eq provider) and (Identities.subject eq subject) }
            .singleOrNull()
            ?.get(Identities.playerId)
}
