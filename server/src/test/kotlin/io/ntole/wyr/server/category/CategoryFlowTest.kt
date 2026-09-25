package io.ntole.wyr.server.category

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.category.CategoryDto
import io.ntole.wyr.core.category.CategoryListDto
import io.ntole.wyr.server.NO_PRACTICAL_LIMIT
import io.ntole.wyr.server.config.ServerConfig
import io.ntole.wyr.server.testDatabaseFor
import io.ntole.wyr.server.wyrModule
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** The categories end to end (CLAUDE.md §8d, *Categories*): listed to anybody, oldest first. */
class CategoryFlowTest {
    @Test
    fun `the categories are listed to anybody oldest first with both their names`() =
        runServer("listed") { client ->
            val response = client.categories()

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(FIRST_CATEGORIES, response.body<CategoryListDto>().categories)
        }

    @Test
    fun `a bearer token beside the request plays no part`() =
        runServer("bearer") { client ->
            val response = client.categories { bearerAuth("not a token this server signed") }

            assertEquals(HttpStatusCode.OK, response.status, "no 401 for a token it does not read")
            assertEquals(FIRST_CATEGORIES, response.body<CategoryListDto>().categories)
        }

    private suspend fun HttpClient.categories(build: HttpRequestBuilder.() -> Unit = {}): HttpResponse =
        get(WyrApi.Paths.CATEGORIES, build)

    private fun runServer(
        databaseName: String,
        block: suspend (HttpClient) -> Unit,
    ) = testApplication {
        val database = testDatabaseFor("categories-$databaseName")
        application {
            wyrModule(
                ServerConfig(
                    port = 0,
                    jdbcUrl = database.jdbcUrl,
                    dbUser = database.user,
                    dbPassword = database.password,
                    jwtSecret = "test-secret",
                    jwtIssuer = "wyr-test",
                    jwtAudience = "wyr-test-client",
                    accessTokenTtlSeconds = 300,
                    refreshTokenTtlSeconds = 3_600,
                    refreshGraceSeconds = null,
                    allowedWebOrigins = emptyList(),
                    adminToken = ADMIN_TOKEN,
                    rateLimits = NO_PRACTICAL_LIMIT,
                    clientIpHeader = null,
                    onRender = false,
                ),
            )
        }
        block(createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } })
    }

    private companion object {
        const val ADMIN_TOKEN = "test-admin-token-long-enough-to-pass-the-length-check"

        /** The five V6 writes, as the list sends them, Serbian names in Cyrillic and all. */
        val FIRST_CATEGORIES: List<CategoryDto> =
            listOf(
                CategoryDto("FOOD", "Храна", "Food"),
                CategoryDto("LIFESTYLE", "Начин живота", "Lifestyle"),
                CategoryDto("ETHICS", "Етика", "Ethics"),
                CategoryDto("SUPERPOWERS", "Супермоћи", "Superpowers"),
                CategoryDto("ABSURD", "Апсурдно", "Absurd"),
            )
    }
}
