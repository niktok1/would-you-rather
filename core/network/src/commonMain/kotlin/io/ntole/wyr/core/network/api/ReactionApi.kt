package io.ntole.wyr.core.network.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.reaction.ReactionRequest
import io.ntole.wyr.core.reaction.ReactionResultDto

public class ReactionApi(
    private val client: HttpClient,
) {
    /**
     * Sets what the session player thinks of a question, answered with where its reactions then stand.
     * Requires a session: the Auth plugin attaches the bearer token. The request sets rather than
     * toggles (CLAUDE.md §8d, *Reactions*), so sending it again changes nothing. A refusal throws, as
     * every non-2xx does, with the server's code: 404 for a question no player is served.
     */
    public suspend fun setReaction(request: ReactionRequest): ReactionResultDto =
        client
            .post(WyrApi.Paths.REACTIONS) {
                setBody(request)
            }.body()
}
