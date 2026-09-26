package io.ntole.wyr.core.network.api

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.push.PushTokenRequest

/**
 * This device's push token (CLAUDE.md §8a, *Push tokens*). Requires a session: the Auth plugin attaches
 * the bearer token, and the server keeps the token under the session it names.
 */
public class PushApi(
    private val client: HttpClient,
) {
    /** Registers [request]'s token for the session player, answered 204. */
    public suspend fun register(request: PushTokenRequest) {
        client.post(WyrApi.Paths.MY_PUSH_TOKENS) { setBody(request) }
    }
}
