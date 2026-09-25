package io.ntole.wyr.core.network.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.category.CategoryListDto

public class CategoryApi(
    private val client: HttpClient,
) {
    /**
     * Every category the server has, oldest first (CLAUDE.md §8d, *Categories*). Needs no session:
     * the server reads none, so the Auth plugin's bearer, when there is one, plays no part, and the
     * route never answers 401.
     */
    public suspend fun all(): CategoryListDto = client.get(WyrApi.Paths.CATEGORIES).body()
}
