package io.ntole.wyr.core.data.session

import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.data.mapper.runApi
import io.ntole.wyr.core.domain.session.SessionRepository
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.api.AuthApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Zero-click sessions: reuse the stored one, otherwise ask the server for a guest.
 *
 * The mutex is the important part. On a cold start the UI can easily fire two calls that both
 * need a session; without serialising here, both would see an empty store and mint a *separate*
 * guest player, silently splitting the player's points across two accounts.
 */
public class DefaultSessionRepository(
    private val authApi: AuthApi,
    private val sessionStore: SessionStore,
) : SessionRepository {
    private val mutex = Mutex()

    override suspend fun ensure(): String =
        mutex.withLock {
            sessionStore.read()?.playerId ?: mintGuest()
        }

    override suspend fun currentPlayerId(): String? = sessionStore.read()?.playerId

    override suspend fun clear(): Unit = mutex.withLock { persist { sessionStore.clear() } }

    /** The session as stored right now, for [withSessionRecovery] to capture before a call. */
    internal fun storedSession(): SessionDto? = sessionStore.read()

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
     *   to this one, as it does once its grace window has passed (CLAUDE.md §8a); within it, two
     *   such refreshes both go through. The session is alive; this request only lost the race.
     *
     * That second case keeps the player id, which is why the whole session is compared. A refresh
     * the failed call made itself changes the store too, and deferring recovery to the next call
     * then costs nothing: the server only refreshes a player it still has.
     * Remaining edge: if the refusal arrives before the other client has written its refreshed
     * session, the live session still looks dead and is replaced. Closing that would need a lock
     * shared across processes.
     *
     * The honest cost of guest-only auth: the old player row is orphaned, so points earned
     * against it are gone. Linking a provider account is what will remove this cliff.
     *
     * Returns the player to retry as.
     */
    internal suspend fun resetIfStill(sentWith: SessionDto?): String =
        mutex.withLock {
            val stored = sessionStore.read()
            if (stored != null && stored != sentWith) return@withLock stored.playerId

            persist { sessionStore.clear() }
            mintGuest()
        }

    private suspend fun mintGuest(): String {
        val session = runApi { authApi.guest() }
        persist { sessionStore.write(session) }
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
