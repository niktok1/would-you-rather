package io.ntole.wyr.core.network.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.vote.VoteRequest
import io.ntole.wyr.core.vote.VoteResultDto

public class VoteApi(
    private val client: HttpClient,
) {
    /** Requires a session: the Auth plugin attaches the bearer token. */
    public suspend fun cast(request: VoteRequest): VoteResultDto =
        client
            .post(WyrApi.Paths.VOTES) {
                setBody(request)
            }.body()
}
