package io.ntole.wyr.core.data.session

import io.ntole.wyr.core.data.mapper.runApi
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException

/**
 * Runs [call] through [runApi], recovering once from a session the server has stopped accepting.
 *
 * Ktor's bearer provider already refreshes an expired access token transparently. This handles
 * the case it cannot: the session itself is dead — the refresh token is rejected (server restarted
 * with a new signing key, row pruned, storage restored from an old backup, a rollback to a build
 * that refreshes only the device used last) or the server no longer knows the player. Without it, a
 * player in that state would see every authenticated call fail forever with no way out but
 * reinstalling. The session opened in its place is the same player's where this device keeps their
 * recovery secret, and a fresh guest's where it keeps none (CLAUDE.md §8a, *Recovery*).
 *
 * Exactly one retry: if the session opened in its place is refused as well, the failure is real and
 * goes to the caller. So does a failure to open one: a recovery that fails on the network mints no
 * guest, and the next call tries again.
 */
internal suspend fun <T> DefaultSessionRepository.withSessionRecovery(call: suspend () -> T): T {
    // Captured before the call goes out. By the time it fails, a concurrent caller may already
    // have replaced the dead session, or another client sharing the store refreshed it, and
    // reading the store then would reset the new one too.
    val sentWith = storedSession()
    return try {
        runApi(call)
    } catch (failure: WyrException) {
        if (failure.error != DomainError.UNAUTHORIZED) throw failure

        resetIfStill(sentWith)
        runApi(call)
    }
}
