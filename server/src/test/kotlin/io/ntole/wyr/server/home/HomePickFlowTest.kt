package io.ntole.wyr.server.home

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.core.home.HomePickRequest
import io.ntole.wyr.core.home.HomePicksDto
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.server.db.TEST_SEEDS
import io.ntole.wyr.server.testDatabaseFor
import io.ntole.wyr.server.testServerConfig
import io.ntole.wyr.server.wyrModule
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Home screen's two Play buttons end to end (CLAUDE.md §8d, *Home picks*). */
class HomePickFlowTest {
    @Test
    fun `the counts need no session and start at zero`() =
        runServer("zero") { client ->
            val response = client.get(WyrApi.Paths.HOME_PICKS)

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(HomePicksDto(picksA = 0, picksB = 0), response.body())
        }

    @Test
    fun `every tap counts and is answered with both counts after it`() =
        runServer("taps") { client ->
            val first = client.guest()
            val second = client.guest()

            assertEquals(HomePicksDto(picksA = 1, picksB = 0), client.pick(first, OptionSide.A).body())
            // A player's repeats count too: every tap is one.
            assertEquals(HomePicksDto(picksA = 2, picksB = 0), client.pick(first, OptionSide.A).body())
            assertEquals(HomePicksDto(picksA = 2, picksB = 1), client.pick(second, OptionSide.B).body())

            assertEquals(HomePicksDto(picksA = 2, picksB = 1), client.get(WyrApi.Paths.HOME_PICKS).body())
        }

    @Test
    fun `a tap needs a session and counts nothing without one`() =
        runServer("no-session") { client ->
            val response = client.pick(session = null, OptionSide.A)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ErrorCode.UNAUTHORIZED, response.body<ErrorDto>().code)
            assertEquals(HomePicksDto(picksA = 0, picksB = 0), client.get(WyrApi.Paths.HOME_PICKS).body())
        }

    @Test
    fun `a tap naming no side is refused as malformed and counts nothing`() =
        runServer("malformed") { client ->
            val session = client.guest()

            for (body in listOf("{}", """{"side":"C"}""", "not json")) {
                val response =
                    client.post(WyrApi.Paths.HOME_PICKS) {
                        bearerAuth(session.accessToken)
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status, body)
                assertEquals(ErrorCode.VALIDATION_FAILED, response.body<ErrorDto>().code, body)
            }
            assertEquals(HomePicksDto(picksA = 0, picksB = 0), client.get(WyrApi.Paths.HOME_PICKS).body())
        }

    private suspend fun HttpClient.guest(): SessionDto = post(WyrApi.Paths.AUTH_GUEST).body()

    private suspend fun HttpClient.pick(
        session: SessionDto?,
        side: OptionSide,
    ): HttpResponse =
        post(WyrApi.Paths.HOME_PICKS) {
            session?.let { bearerAuth(it.accessToken) }
            contentType(ContentType.Application.Json)
            setBody(HomePickRequest(side))
        }

    private fun runServer(
        databaseName: String,
        block: suspend (HttpClient) -> Unit,
    ) = testApplication {
        val database = testDatabaseFor("home-picks-$databaseName")
        application { wyrModule(testServerConfig(database), TEST_SEEDS) }
        block(createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } })
    }
}
