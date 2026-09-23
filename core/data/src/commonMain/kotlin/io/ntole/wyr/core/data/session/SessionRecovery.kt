package io.ntole.wyr.core.data.session

import io.ntole.wyr.core.data.mapper.runApi
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException

/**
 * Runs [call] through [runApi], recovering once from a session the server has stopped accepting.
 *
 * Ktor's bearer provider already refreshes an expired access token transparently. This handles
 * the case it cannot: the session itself is dead — the refresh token is rejected (server restarted
 * with a new signing key, row pruned, storage restored from an old backup) or the server no longer
 * knows the player. Without it, a player in that state would see every authenticated call fail
 * forever with no way out but reinstalling.
 *
 * Exactly one retry: if a freshly minted guest is refused as well, the failure is real and goes
 * to the caller.
 */
internal suspend fun <T> DefaultSessionRepository.withSessionRecovery(call: suspend () -> T): T =
    try {
        runApi(call)
    } catch (failure: WyrException) {
        if (failure.error != DomainError.UNAUTHORIZED) throw failure

        reset()
        runApi(call)
    }
