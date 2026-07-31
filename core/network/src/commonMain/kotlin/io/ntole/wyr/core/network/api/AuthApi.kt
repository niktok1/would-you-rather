package io.ntole.wyr.core.network.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.RefreshRequest
import io.ntole.wyr.core.auth.SessionDto

/**
 * Session endpoints. Both are unauthenticated — [guest] has no credential yet and [refresh]
 * carries its credential in the body.
 */
public class AuthApi(
    private val client: HttpClient,
) {
    /** Mints a brand-new server-issued guest player. Zero player interaction. */
    public suspend fun guest(): SessionDto = client.post(WyrApi.Paths.AUTH_GUEST).body()

    public suspend fun refresh(refreshToken: String): SessionDto =
        client
            .post(WyrApi.Paths.AUTH_REFRESH) {
                setBody(RefreshRequest(refreshToken))
            }.body()
}
