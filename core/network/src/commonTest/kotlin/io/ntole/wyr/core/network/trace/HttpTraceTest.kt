package io.ntole.wyr.core.network.trace

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.network.ApiException
import io.ntole.wyr.core.network.BASE_URL
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.api.QuestionApi
import io.ntole.wyr.core.network.respondEmptyPage
import io.ntole.wyr.core.network.respondErrorDto
import io.ntole.wyr.core.network.respondSession
import io.ntole.wyr.core.network.session
import io.ntole.wyr.core.network.storeHolding
import io.ntole.wyr.core.network.trace.HttpExchange.Outcome.Answered
import io.ntole.wyr.core.network.trace.HttpExchange.Outcome.Failed
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpTraceTest {
    private val trace = HttpTrace()

    @Test
    fun `an answered request is recorded with its method path and status`() =
        runTest {
            questionApi(MockEngine { respondEmptyPage() }).page(cursor = "c1")

            val exchange = trace.exchanges.value.single()
            assertEquals("GET", exchange.method)
            assertEquals("${WyrApi.Paths.QUESTIONS}?limit=20&cursor=c1", exchange.pathAndQuery)
            assertEquals(Answered(200), exchange.outcome)
            assertTrue(exchange.elapsedMillis >= 0)
        }

    @Test
    fun `an error response is recorded with its status`() =
        runTest {
            val api = questionApi(MockEngine { respondErrorDto(HttpStatusCode.Conflict, ErrorCode.ALREADY_VOTED) })

            assertFailsWith<ApiException> { api.page() }

            val exchange = trace.exchanges.value.single()
            assertEquals(Answered(409), exchange.outcome)
        }

    @Test
    fun `a request that got no answer is recorded with what was thrown`() =
        runTest {
            val api = questionApi(MockEngine { throw ConnectTimeoutException("connect timed out") })

            assertFailsWith<ConnectTimeoutException> { api.page() }

            val exchange = trace.exchanges.value.single()
            assertEquals(Failed("ConnectTimeoutException"), exchange.outcome)
        }

    @Test
    fun `a token refresh is recorded between the rejected call and its retry`() =
        runTest {
            questionApi(expiredAccessToken()).page()

            assertEquals(
                listOf(
                    "GET ${WyrApi.Paths.QUESTIONS}?limit=20" to Answered(200),
                    "POST ${WyrApi.Paths.AUTH_REFRESH}" to Answered(200),
                    "GET ${WyrApi.Paths.QUESTIONS}?limit=20" to Answered(401),
                ),
                trace.exchanges.value.map { "${it.method} ${it.pathAndQuery}" to it.outcome },
            )
        }

    @Test
    fun `no credential reaches the trace`() =
        runTest {
            // Both tokens of both sessions cross the wire here: bearers in headers, refresh
            // tokens in the refresh request's body and response.
            questionApi(expiredAccessToken()).page()

            val recorded = trace.exchanges.value.toString()
            listOf("access-a", "refresh-a", "Bearer").forEach { secret ->
                assertFalse(secret in recorded, "\"$secret\" leaked into $recorded")
            }
        }

    @Test
    fun `only the newest exchanges are kept`() =
        runTest {
            val small = HttpTrace(capacity = 2)
            val api =
                QuestionApi(WyrHttpClient.create(BASE_URL, storeHolding(session("a")), okEngine(), small))

            listOf("c1", "c2", "c3").forEach { cursor -> api.page(cursor = cursor) }

            assertEquals(
                listOf("c3", "c2"),
                small.exchanges.value.map { it.pathAndQuery.substringAfter("cursor=") },
            )
        }

    private fun okEngine() = MockEngine { respondEmptyPage() }

    /** Rejects the stored access token, so every call goes 401, refresh, retry. */
    private fun expiredAccessToken() =
        MockEngine { request ->
            when {
                request.url.encodedPath == WyrApi.Paths.AUTH_REFRESH -> respondSession(session("a2"))
                request.headers[HttpHeaders.Authorization] == "Bearer access-a2" -> respondEmptyPage()
                else -> respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
            }
        }

    private fun questionApi(engine: MockEngine): QuestionApi =
        QuestionApi(WyrHttpClient.create(BASE_URL, storeHolding(session("a")), engine, trace))
}
