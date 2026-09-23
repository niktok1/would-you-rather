package io.ntole.wyr.core.data.session

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

    override suspend fun clear(): Unit = mutex.withLock { sessionStore.clear() }

    /**
     * Throw away a session the server no longer accepts and mint a fresh one — unless that has
     * already happened.
     *
     * [failedPlayerId] is who the failed request went out as, captured before it was sent. When
     * several requests fail on one dead session, the first caller here mints the guest; the rest
     * find the store already holds someone else and get that player instead of each minting their
     * own and orphaning all but the last. Returns the player to retry as.
     *
     * The honest cost of guest-only auth: the old player row is orphaned, so points earned
     * against it are gone. Linking a provider account is what will remove this cliff.
     */
    internal suspend fun resetIfStill(failedPlayerId: String?): String =
        mutex.withLock {
            val stored = sessionStore.read()?.playerId
            if (stored != null && stored != failedPlayerId) return@withLock stored

            sessionStore.clear()
            mintGuest()
        }

    private suspend fun mintGuest(): String {
        val session = runApi { authApi.guest() }
        sessionStore.write(session)
        return session.playerId
    }
}
