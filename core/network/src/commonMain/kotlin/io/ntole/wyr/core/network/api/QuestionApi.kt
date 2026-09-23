package io.ntole.wyr.core.network.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionPageDto

public class QuestionApi(
    private val client: HttpClient,
) {
    /**
     * The next batch of the session player's feed. Requires a session: the Auth plugin attaches
     * the bearer token, and the server answers who the batch is for from that alone.
     */
    public suspend fun page(
        limit: Int = WyrApi.Limits.DEFAULT_PAGE_SIZE,
        category: QuestionCategory? = null,
    ): QuestionPageDto =
        client
            .get(WyrApi.Paths.QUESTIONS) {
                parameter(WyrApi.Query.LIMIT, limit)
                // UNKNOWN is a client-side sentinel, never a real filter the server knows.
                category?.takeIf { it != QuestionCategory.UNKNOWN }?.let {
                    parameter(WyrApi.Query.CATEGORY, it.name)
                }
            }.body()
}
