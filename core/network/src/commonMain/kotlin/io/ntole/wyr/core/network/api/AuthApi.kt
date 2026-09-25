package io.ntole.wyr.core.network.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.auth.AuthCircuitBreaker
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.GuestSessionDto
import io.ntole.wyr.core.auth.RecoverRequest
import io.ntole.wyr.core.auth.RecoverySecretDto
import io.ntole.wyr.core.auth.RefreshRequest
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.network.refreshTimeout

/**
 * Session endpoints, and the recovery secret's (CLAUDE.md §8a, *Recovery*). All but
 * [newRecoverySecret] are unauthenticated: [guest] has no credential yet, and [refresh] and [recover]
 * carry theirs in the body.
 */
public class AuthApi(
    private val client: HttpClient,
) {
    /**
     * Mints a brand-new server-issued guest player. Zero player interaction. The answer carries the
     * player's recovery secret beside the session, or none from a server without recovery.
     */
    public suspend fun guest(): GuestSessionDto = client.post(WyrApi.Paths.AUTH_GUEST).body()

    /**
     * Spends [refreshToken], which the server rotates as it answers, so this gets the refresh's
     * longer timeout, as the bearer provider's own refresh does (`WyrHttpClient.REFRESH_TIMEOUT`).
     */
    public suspend fun refresh(refreshToken: String): SessionDto =
        client
            .post(WyrApi.Paths.AUTH_REFRESH) {
                refreshTimeout()
                setBody(RefreshRequest(refreshToken))
            }.body()

    /**
     * Opens a new session for the player whose recovery secret [recoverySecret] is. A secret no player
     * holds is refused as `INVALID_RECOVERY_SECRET`.
     *
     * Outside the bearer provider altogether ([AuthCircuitBreaker], as its own refresh is): no bearer
     * goes with it, and its 401 sets off no refresh, since what that 401 says is dead is the secret,
     * not the session. It spends nothing, the secret staying as it was, so an answer lost is only a
     * session the server opened for nobody, and the ordinary timeout serves.
     */
    public suspend fun recover(recoverySecret: String): SessionDto =
        client
            .post(WyrApi.Paths.AUTH_RECOVER) {
                attributes.put(AuthCircuitBreaker, Unit)
                setBody(RecoverRequest(recoverySecret))
            }.body()

    /**
     * A new recovery secret for the session player, which kills the one they held. Requires a session:
     * the bearer provider attaches it, and refreshes it on a 401, as for any call.
     */
    public suspend fun newRecoverySecret(): RecoverySecretDto = client.post(WyrApi.Paths.MY_RECOVERY_SECRET).body()
}
