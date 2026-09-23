package io.ntole.wyr.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.network.api.QuestionApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * What a failed call throws. The contract: an HTTP error response becomes an [ApiException] with
 * its code and status, and nothing else is disguised as one.
 */
class WyrHttpClientTest {
    @Test
    fun `an error response carries the server's code and status`() =
        runTest {
            val api = questionApi(MockEngine { respondErrorDto(HttpStatusCode.Conflict, ErrorCode.ALREADY_VOTED) })

            val failure = assertFailsWith<ApiException> { api.page() }

            assertEquals(ErrorCode.ALREADY_VOTED, failure.code)
            assertEquals(409, failure.status)
        }

    @Test
    fun `an error page that is not an ErrorDto keeps its status`() =
        runTest {
            val proxyPage =
                MockEngine {
                    respond(
                        "<html><body>503 Service Unavailable</body></html>",
                        HttpStatusCode.ServiceUnavailable,
                        headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()),
                    )
                }

            val failure = assertFailsWith<ApiException> { questionApi(proxyPage).page() }

            assertEquals(ErrorCode.UNKNOWN, failure.code)
            assertEquals(503, failure.status)
        }

    @Test
    fun `a dead refresh token surfaces as INVALID_REFRESH_TOKEN`() =
        runTest {
            // The access token is rejected, so Ktor tries a refresh, and the refresh token is dead
            // too. That nested failure is the one the data layer needs to see to reset the session.
            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        WyrApi.Paths.AUTH_REFRESH -> {
                            respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.INVALID_REFRESH_TOKEN)
                        }

                        else -> {
                            respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
                        }
                    }
                }

            val failure = assertFailsWith<ApiException> { questionApi(engine).page() }

            assertEquals(ErrorCode.INVALID_REFRESH_TOKEN, failure.code)
            assertEquals(401, failure.status)
        }

    @Test
    fun `a request that never got an answer throws what the engine threw`() =
        runTest {
            val offline = MockEngine { throw ConnectTimeoutException("connect timed out") }

            // Not an ApiException: there was no response, and runApi calls this NETWORK.
            assertFailsWith<ConnectTimeoutException> { questionApi(offline).page() }
        }

    @Test
    fun `a cancelled call fails with cancellation`() =
        runTest {
            val requestArrived = CompletableDeferred<Unit>()
            val hangs =
                MockEngine {
                    requestArrived.complete(Unit)
                    awaitCancellation()
                }
            val api = questionApi(hangs)
            var thrown: Throwable? = null

            val call =
                launch {
                    try {
                        api.page()
                    } catch (failure: Throwable) {
                        thrown = failure
                        throw failure
                    }
                }
            requestArrived.await()
            call.cancelAndJoin()

            assertIs<CancellationException>(thrown)
        }

    private fun questionApi(engine: MockEngine): QuestionApi =
        QuestionApi(WyrHttpClient.create(BASE_URL, storeHolding(session("a")), engine))
}
