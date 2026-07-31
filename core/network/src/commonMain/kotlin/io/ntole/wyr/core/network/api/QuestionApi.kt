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
    public suspend fun page(
        cursor: String? = null,
        limit: Int = WyrApi.Limits.DEFAULT_PAGE_SIZE,
        category: QuestionCategory? = null,
    ): QuestionPageDto =
        client
            .get(WyrApi.Paths.QUESTIONS) {
                parameter(WyrApi.Query.LIMIT, limit)
                cursor?.let { parameter(WyrApi.Query.CURSOR, it) }
                // UNKNOWN is a client-side sentinel, never a real filter the server knows.
                category?.takeIf { it != QuestionCategory.UNKNOWN }?.let {
                    parameter(WyrApi.Query.CATEGORY, it.name)
                }
            }.body()
}
