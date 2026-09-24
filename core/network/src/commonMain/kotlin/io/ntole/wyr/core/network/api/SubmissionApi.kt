package io.ntole.wyr.core.network.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.question.SubmissionListDto
import io.ntole.wyr.core.question.SubmitQuestionRequest

/**
 * The session player's own questions (CLAUDE.md §8d). Both calls require a session: the Auth
 * plugin attaches the bearer token, and the server takes the author from that alone.
 */
public class SubmissionApi(
    private val client: HttpClient,
) {
    /**
     * Submits [request], answered 201 with the submission as stored: options trimmed, categories
     * each once in declaration order, and pending. A refusal throws, as every non-2xx does, with the
     * server's code: 422 for options the rules refuse, 409 for one pending submission too many.
     */
    public suspend fun submit(request: SubmitQuestionRequest): SubmissionDto =
        client
            .post(WyrApi.Paths.QUESTIONS) {
                setBody(request)
            }.body()

    /** Every question the session player has submitted, whatever its status, newest first. */
    public suspend fun mine(): SubmissionListDto = client.get(WyrApi.Paths.MY_QUESTIONS).body()
}
