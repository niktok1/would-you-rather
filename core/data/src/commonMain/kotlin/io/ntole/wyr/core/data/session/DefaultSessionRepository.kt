package io.ntole.wyr.core.data.session

import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.data.mapper.runApi
import io.ntole.wyr.core.domain.session.CurrentSession
import io.ntole.wyr.core.domain.session.SessionRepository
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.api.AuthApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Zero-click sessions: reuse the stored one, otherwise ask the server for a guest (CLAUDE.md §8a).
 * Every session it stores is told to [sessions] once, and whether who plays here is settled, for a
 * launch's Play Games sign-in, is kept beside it ([PlayGamesSettled]).
 *
 * The mutex is the important part. On a cold start the UI can easily fire two calls that both
 * need a session; without serialising here, both would see an empty store and mint a *separate*
 * guest player, silently splitting the player's points across two accounts.
 */
public class DefaultSessionRepository(
    private val authApi: AuthApi,
    private val sessionStore: SessionStore,
    private val playGamesSettled: PlayGamesSettled = PlayGamesSettled(),
) : SessionRepository,
    CurrentSession {
    private val mutex = Mutex()

    /** Counts every session this repository stores or drops, so [sessions] hears each once. */
    private val changes = MutableStateFlow(0L)

    override suspend fun ensure(): String =
        mutex.withLock {
            sessionStore.read()?.playerId ?: mintGuest()
        }

    override fun current(): String? = sessionStore.read()?.playerId

    override val sessions: Flow<String> = changes.map { current() }.filterNotNull()

    /** Whether who plays on this device is settled, so a launch signs in with Play Games no more. */
    internal fun isPlayGamesSettled(): Boolean = playGamesSettled.isSettled()

    /**
     * Drops the stored session, for a logout: the next [ensure] mints a fresh guest. The player chose
     * it, so a launch signs the guest in with Play Games no more ([PlayGamesSettled]).
     */
    internal suspend fun clear(): Unit =
        mutex.withLock {
            persist {
                sessionStore.clear()
                playGamesSettled.settle()
            }
            changes.update { it + 1 }
        }

    /** The session as stored right now, for [withSessionRecovery] to capture before a call. */
    internal fun storedSession(): SessionDto? = sessionStore.read()

    /**
     * Stores [session], a login's or a Play Games sign-in's, in place of whatever is stored, under the
     * lock minting takes: a guest being minted meanwhile lands first and is replaced, and one asked for
     * after finds this. A refresh in flight for the session before drops its answer, since the store has
     * moved on. The player chose it, so it settles who plays here ([PlayGamesSettled]).
     */
    internal suspend fun replace(session: SessionDto): Unit =
        mutex.withLock {
            persist {
                sessionStore.write(session)
                playGamesSettled.settle()
            }
            changes.update { it + 1 }
        }

    /**
     * Stores [session] as [replace] does, but only while the device still plays as [sentAs], the player
     * the request that answered it went out as: a login or a logout that landed while it was in flight
     * is the player's later choice, which the answer must not undo. Compared by player, since a refresh
     * meanwhile keeps the player and rotates the session. Returns whether it stored it.
     */
    internal suspend fun replaceIfStill(
        sentAs: String?,
        session: SessionDto,
    ): Boolean =
        mutex.withLock {
            if (sessionStore.read()?.playerId != sentAs) return@withLock false
            persist {
                sessionStore.write(session)
                playGamesSettled.settle()
            }
            changes.update { it + 1 }
            true
        }

    /**
     * Throw away a session the server no longer accepts and mint a fresh one — unless that has
     * already happened.
     *
     * [sentWith] is the session the failed request went out with, captured before it was sent.
     * Anything else in the store means the session changed after that, and the call is retried
     * as whatever is stored now:
     * - Another call that failed on the same dead session got here first and minted a guest.
     *   Minting again would orphan that guest.
     * - Another client sharing this store (a second browser tab, a second desktop instance)
     *   refreshed it first. The server rotated the refresh token for that client and refused it
     *   to this one, as it does when the token was already the previous one, or with its grace
     *   window off or past a bound (CLAUDE.md §8a); otherwise two such refreshes both go through.
     *   The session is alive; this request only lost the race.
     *
     * That second case keeps the player id, which is why the whole session is compared. A refresh
     * the failed call made itself changes the store too, and deferring recovery to the next call
     * then costs nothing: the server only refreshes a player it still has.
     * Remaining edge: if the refusal arrives before the other client has written its refreshed
     * session, the live session still looks dead and is replaced. Closing that would need a lock
     * shared across processes.
     *
     * The honest cost of a guest session: the old player row is orphaned, so points earned against
     * it are gone (CLAUDE.md §8a, *Known limitation*).
     *
     * Returns the player to retry as.
     */
    internal suspend fun resetIfStill(sentWith: SessionDto?): String =
        mutex.withLock {
            val stored = sessionStore.read()
            if (stored != null && stored != sentWith) return@withLock stored.playerId

            // Nobody chose this: the next launch signs the fresh guest in with Play Games, which may
            // bring back the player the dead session was (CLAUDE.md §8a, *Play Games sign-in*).
            persist {
                sessionStore.clear()
                playGamesSettled.forget()
            }
            changes.update { it + 1 }
            mintGuest()
        }

    private suspend fun mintGuest(): String {
        val session = runApi { authApi.guest() }
        persist { sessionStore.write(session) }
        changes.update { it + 1 }
        return session.playerId
    }

    /**
     * Changes the stored session through [runApi], so a change the platform could not make durable
     * (Android's `commit()` on a full disk, the JVM's `flush()`) fails the call as a `WyrException`
     * rather than escaping the data layer. It is NETWORK, as the same write already is when it
     * fails inside a token refresh. Both platforms keep the change in memory, so the next call
     * carries on with it; it is the next start that would not have it.
     */
    private suspend fun persist(change: suspend () -> Unit): Unit = runApi(change)
}
