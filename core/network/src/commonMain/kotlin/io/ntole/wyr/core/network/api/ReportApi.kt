package io.ntole.wyr.core.network.api

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.report.HideAuthorRequest
import io.ntole.wyr.core.report.HideQuestionRequest
import io.ntole.wyr.core.report.ReportRequest

/**
 * Reports a question, or hides it or its author from the session player (CLAUDE.md §8d, *Reports*).
 * Each requires a session, which the Auth plugin attaches, and is answered 204 with no body, so there
 * is nothing to read back; a refusal still throws, as every non-2xx does, with the server's code: 404
 * for a question no player is served.
 */
public class ReportApi(
    private val client: HttpClient,
) {
    public suspend fun report(request: ReportRequest) {
        client.post(WyrApi.Paths.REPORTS) { setBody(request) }
    }

    public suspend fun hideQuestion(request: HideQuestionRequest) {
        client.post(WyrApi.Paths.HIDDEN_QUESTIONS) { setBody(request) }
    }

    public suspend fun hideAuthor(request: HideAuthorRequest) {
        client.post(WyrApi.Paths.HIDDEN_AUTHORS) { setBody(request) }
    }
}
