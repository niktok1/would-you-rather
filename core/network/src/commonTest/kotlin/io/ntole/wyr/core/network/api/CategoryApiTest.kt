package io.ntole.wyr.core.network.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.category.CategoryDto
import io.ntole.wyr.core.category.CategoryListDto
import io.ntole.wyr.core.network.BASE_URL
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.WyrJson
import io.ntole.wyr.core.network.jsonHeaders
import io.ntole.wyr.core.network.storeHolding
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** What reading the categories asks for, and what it reads back. */
class CategoryApiTest {
    @Test
    fun `the categories are read from their own path in the order the server sent them`() =
        runTest {
            val listed =
                CategoryListDto(
                    listOf(
                        CategoryDto(id = "FOOD", nameSr = "Храна", nameEn = "Food"),
                        CategoryDto(id = "ABSURD", nameSr = "Апсурдно", nameEn = "Absurd"),
                    ),
                )
            val engine = MockEngine { respond(WyrJson.encodeToString(listed), HttpStatusCode.OK, jsonHeaders) }

            // No session at all: the list needs none.
            val categories = CategoryApi(WyrHttpClient.create(BASE_URL, storeHolding(null), engine)).all()

            assertEquals(listed, categories)
            val sent = engine.requestHistory.single()
            assertEquals(HttpMethod.Get, sent.method)
            assertEquals(WyrApi.Paths.CATEGORIES, sent.url.encodedPath)
        }
}
