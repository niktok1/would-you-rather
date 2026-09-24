package io.ntole.wyr.core.network.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.like.LikeRequest
import io.ntole.wyr.core.like.LikeResultDto

public class LikeApi(
    private val client: HttpClient,
) {
    /**
     * Sets the session player's like of a question, answered with where its likes then stand.
     * Requires a session: the Auth plugin attaches the bearer token. The request sets rather than
     * toggles (CLAUDE.md §8d), so sending it again changes nothing. A refusal throws, as every
     * non-2xx does, with the server's code: 404 for a question no player is served.
     */
    public suspend fun setLiked(request: LikeRequest): LikeResultDto =
        client
            .post(WyrApi.Paths.LIKES) {
                setBody(request)
            }.body()
}
