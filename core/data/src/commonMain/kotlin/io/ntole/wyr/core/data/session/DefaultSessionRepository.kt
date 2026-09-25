package io.ntole.wyr.core.data.session

import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.data.mapper.runApi
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.session.SessionRepository
import io.ntole.wyr.core.network.RecoverySecretStore
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.api.AuthApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Zero-click sessions: reuse the stored one, otherwise recover the player whose recovery secret this
 * device keeps, otherwise ask the server for a guest (CLAUDE.md §8a, *Recovery*).
 *
 * The mutex is the important part. On a cold start the UI can easily fire two calls that both
 * need a session; without serialising here, both would see an empty store and mint a *separate*
 * guest player, silently splitting the player's points across two accounts. Everything that touches
 * the recovery secret holds it too, so no two requests for a secret can each kill the other's.
 *
 * [recovery] is null where the platform keeps no recovery secret, as on desktop and web: a session is
 * then only ever minted, and nothing asks the server for a secret.
 */
public class DefaultSessionRepository(
    private val authApi: AuthApi,
    private val sessionStore: SessionStore,
    private val recovery: RecoverySecretStore? = null,
) : SessionRepository {
    private val mutex = Mutex()

    /** The player whose secret this process has seen to, so [keepRecoverySecret] runs once a launch. */
    private var secretSeenToFor: String? = null

    override suspend fun ensure(): String =
        mutex.withLock {
            val playerId = sessionStore.read()?.playerId ?: return@withLock openSession()
            keepRecoverySecret(playerId)
            playerId
        }

    override suspend fun currentPlayerId(): String? = sessionStore.read()?.playerId

    /** The secret goes first: left behind, it would recover the player being cleared away at the next [ensure]. */
    override suspend fun clear(): Unit =
        mutex.withLock {
            recovery?.let { store -> persist { store.clear() } }
            persist { sessionStore.clear() }
        }

    /**
     * What deleting the app and installing it again leaves: the secret, and nothing that was kept
     * beside the session, so the count of failed requests for a secret goes with it. The next [ensure]
     * then recovers the player as a new phone would, in a session of its own.
     */
    override suspend fun clearKeepingSecret(): Unit =
        mutex.withLock {
            persist { sessionStore.clear() }
            recovery?.let { store -> persist { store.clearFailedRequests() } }
            secretSeenToFor = null
        }

    /** The session as stored right now, for [withSessionRecovery] to capture before a call. */
    internal fun storedSession(): SessionDto? = sessionStore.read()

    /**
     * Throw away a session the server no longer accepts and open another in its place — unless that
     * has already happened.
     *
     * [sentWith] is the session the failed request went out with, captured before it was sent.
     * Anything else in the store means the session changed after that, and the call is retried
     * as whatever is stored now:
     * - Another call that failed on the same dead session got here first and opened one. Opening
     *   another would recover the player again, or mint a second guest and orphan the first.
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
     * Where this device keeps a recovery secret, the session opened in its place is the secret's
     * player's, so a dead session costs nothing ([openSession]). The honest cost of guest-only auth
     * remains where it keeps none, on desktop and web, or where the server refuses the secret: the
     * old player row is orphaned, and the points earned against it are gone.
     *
     * Returns the player to retry as.
     */
    internal suspend fun resetIfStill(sentWith: SessionDto?): String =
        mutex.withLock {
            val stored = sessionStore.read()
            if (stored != null && stored != sentWith) return@withLock stored.playerId

            persist { sessionStore.clear() }
            openSession()
        }

    /**
     * Opens a session where none is stored: as the player this device keeps a recovery secret for, or
     * else as a fresh guest.
     *
     * A secret the server does not know will recover nobody, ever (the dev server forgets every one
     * when it restarts), so it is dropped and a guest minted. Any other failure of the recovery mints
     * nothing, whether offline, a 5xx, a 429, or a server from before recovery answering 404: a mint
     * would keep its own secret in place of this one, and lose the account this one recovers. The
     * failure goes to the caller, and the next call tries the secret again.
     *
     * A store that cannot be read counts as holding none, so a phone without Play services plays as
     * a guest rather than not at all. The price: a phone whose store only failed for a moment, as the
     * app first starts on a restored phone, mints a guest and keeps its secret in place of the one it
     * could not read.
     */
    private suspend fun openSession(): String {
        val secret = recovery?.let { store -> bestEffort { store.read() } }
        val playerId = secret?.let { recoverWith(it) } ?: mintGuest()
        // Seen to, one way or another: kept as it was, kept from the mint, or none to keep this launch.
        secretSeenToFor = playerId
        return playerId
    }

    /** The player [secret] recovers, in a session stored for this device, or null when it recovers nobody. */
    private suspend fun recoverWith(secret: String): String? {
        val session =
            try {
                runApi { authApi.recover(secret) }
            } catch (refused: WyrException) {
                if (refused.error != DomainError.INVALID_RECOVERY_SECRET) throw refused
                // Dead for good, so no later start sends it again. Should dropping it fail, the mint
                // that follows keeps its own in its place.
                recovery?.let { store -> bestEffort { store.clear() } }
                return null
            }
        persist { sessionStore.write(session) }
        return session.playerId
    }

    /**
     * A fresh guest, stored, and its recovery secret kept where this device keeps one. The secret goes
     * first: should the session's write then fail, or the app be killed before it, the next start
     * recovers this same player rather than mint another. A secret that cannot be kept costs only the
     * recovery until a later launch asks for another ([keepRecoverySecret]). A server from before
     * recovery sends none.
     */
    private suspend fun mintGuest(): String {
        val guest = runApi { authApi.guest() }
        val secret = guest.recoverySecret
        if (secret != null) recovery?.let { store -> bestEffort { store.write(secret) } }
        persist { sessionStore.write(guest.session()) }
        return guest.playerId
    }

    /**
     * Makes sure this device keeps a recovery secret for [playerId], the session's player, once a
     * launch. A guest minted before recovery has none, and neither has one whose secret could not be
     * kept: either would be lost with this device's storage.
     *
     * Asks the server for one only when the store holds none. Asking kills the player's secret
     * wherever else it is kept, so a store that cannot be read asks nothing, and one that holds a
     * secret keeps it, whoever's it is. On iOS the Keychain item is shared by this person's iPhones
     * (CLAUDE.md §8a), and another player's there is the account the others share, which a new secret
     * would take from them; this device's own guest then stays bound to it, and should its session
     * die, it recovers as that account.
     *
     * Best effort, and never fails [ensure]. A request that failed on the network is tried again at
     * the next launch. One the server refused, or whose secret the store could not keep, counts, and
     * after [MAX_FAILED_SECRET_REQUESTS] of them this install asks no more for the player: a server
     * without recovery refuses every one. A new player, or a reinstall, starts the count again.
     */
    private suspend fun keepRecoverySecret(playerId: String) {
        val store = recovery ?: return
        if (secretSeenToFor == playerId) return
        secretSeenToFor = playerId

        val held =
            try {
                store.read()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unreadable: Exception) {
                return
            }
        if (held != null || store.failedRequests(playerId) >= MAX_FAILED_SECRET_REQUESTS) return

        val failure =
            try {
                store.write(runApi { authApi.newRecoverySecret() }.recoverySecret)
                null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failed: Exception) {
                failed
            }
        when {
            failure == null -> bestEffort { store.clearFailedRequests() }
            (failure as? WyrException)?.error == DomainError.NETWORK -> Unit
            else -> bestEffort { store.countFailedRequest(playerId) }
        }
    }

    /**
     * Changes the stored session through [runApi], so a change the platform could not make durable
     * (Android's `commit()` on a full disk, the JVM's `flush()`) fails the call as a `WyrException`
     * rather than escaping the data layer. It is NETWORK, as the same write already is when it
     * fails inside a token refresh. Both platforms keep the change in memory, so the next call
     * carries on with it; it is the next start that would not have it.
     */
    private suspend fun persist(change: suspend () -> Unit): Unit = runApi(change)

    internal companion object {
        /** How many requests for a secret one player's install makes in vain before it stops asking. */
        const val MAX_FAILED_SECRET_REQUESTS: Int = 3
    }
}

/**
 * [block]'s result, or null when it threw anything but cancellation (CLAUDE.md §5): for the recovery
 * secret's store, whose failures the session can live with.
 */
private suspend fun <T> bestEffort(block: suspend () -> T): T? =
    try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failed: Exception) {
        null
    }
