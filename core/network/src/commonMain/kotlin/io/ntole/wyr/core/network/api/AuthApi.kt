package io.ntole.wyr.core.network.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.auth.AuthCircuitBreaker
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.AccountDto
import io.ntole.wyr.core.auth.LoginRequest
import io.ntole.wyr.core.auth.PlayGamesSignInRequest
import io.ntole.wyr.core.auth.RefreshRequest
import io.ntole.wyr.core.auth.RegisterRequest
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.network.refreshTimeout

/**
 * Session and account endpoints (CLAUDE.md §8a). [guest], [refresh] and [logIn] need no session:
 * [guest] has no credential yet, and [refresh] and [logIn] carry theirs in the body. [register],
 * [playGames] and [logOut] go with the session's bearer, as any other call does.
 */
public class AuthApi(
    private val client: HttpClient,
) {
    /**
     * Mints a brand-new server-issued guest player. Zero player interaction. Read as the session
     * alone, whatever else the answer carries.
     */
    public suspend fun guest(): SessionDto = client.post(WyrApi.Paths.AUTH_GUEST).body()

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
     * Registers the session player, a guest, as an account, keeping everything they have, answered
     * with the username as the server keeps it, lower-cased.
     */
    public suspend fun register(request: RegisterRequest): AccountDto =
        client.post(WyrApi.Paths.AUTH_REGISTER) { setBody(request) }.body()

    /**
     * Logs in to [request]'s account, answered with a new session of that player, this device's own.
     *
     * Sent past the Auth plugin (`AuthCircuitBreaker`), as its own refresh is: a wrong password is a
     * 401 too, which the plugin would take for an expired access token and answer by refreshing the
     * session this device holds and sending the login again. So the 401 comes back as it is, and no
     * bearer goes out with the login, which reads none.
     */
    public suspend fun logIn(request: LoginRequest): SessionDto =
        client
            .post(WyrApi.Paths.AUTH_LOGIN) {
                attributes.put(AuthCircuitBreaker, Unit)
                setBody(request)
            }.body()

    /**
     * Signs in with Google Play Games Services, [request] carrying the one-time server auth code Play
     * Games gave the app, answered with a new session, this device's own, of the player Play Games names
     * (CLAUDE.md §8a, *Play Games sign-in*): the session player, now linked, or the one it was linked to
     * already. Sent with the session's bearer, as any call is: an expired access token is a 401 before
     * the code goes anywhere, so the plugin's refresh and retry send it still unspent.
     */
    public suspend fun playGames(request: PlayGamesSignInRequest): SessionDto =
        client.post(WyrApi.Paths.AUTH_PLAY_GAMES) { setBody(request) }.body()

    /**
     * Ends the session the bearer names, this device's, and no other. Answered 204, as it is for a
     * session already ended. An expired access token is refreshed first, as for any call.
     */
    public suspend fun logOut() {
        client.post(WyrApi.Paths.AUTH_LOGOUT)
    }
}
