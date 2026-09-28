package io.ntole.wyr.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headers
import io.ktor.http.headersOf
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
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
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * What a failed call throws. The contract: an HTTP error response becomes an [ApiException] with
 * its code and status, and nothing else is disguised as one.
 */
class WyrHttpClientTest {
    /** Every request of the game's names its build, a refresh's included (CLAUDE.md §8b, *Minimum client version*). */
    @Test
    fun `every request names the build it comes from`() =
        runTest {
            val named = mutableListOf<Pair<String?, String?>>()
            var calls = 0
            val engine =
                MockEngine { request ->
                    named +=
                        request.headers[WyrApi.Headers.CLIENT_PLATFORM] to
                        request.headers[WyrApi.Headers.CLIENT_VERSION]
                    calls++
                    when {
                        // The first call's access token has expired: the refresh goes out and the call again.
                        calls == 1 -> respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)

                        request.url.encodedPath == WyrApi.Paths.AUTH_REFRESH -> respondSession(session("a"))

                        else -> respondEmptyPage()
                    }
                }
            val build = ClientBuild(WyrApi.ClientPlatform.ANDROID, 10203)

            QuestionApi(WyrHttpClient.create(BASE_URL, storeHolding(session("a")), engine, build = build)).page()

            assertEquals(List<Pair<String?, String?>>(3) { WyrApi.ClientPlatform.ANDROID to "10203" }, named)
        }

    /** The moderation app's client, and one whose build could not read its number, names none. */
    @Test
    fun `a client with no build names none`() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertNull(request.headers[WyrApi.Headers.CLIENT_PLATFORM])
                    assertNull(request.headers[WyrApi.Headers.CLIENT_VERSION])
                    respondEmptyPage()
                }

            QuestionApi(WyrHttpClient.create(BASE_URL, storeHolding(session("a")), engine)).page()
        }

    @Test
    fun `an answer that the build is too old raises the upgrade signal`() =
        runTest {
            val upgrade = UpgradeSignal()
            val engine = MockEngine { respondErrorDto(HttpStatusCode.UpgradeRequired, ErrorCode.UPGRADE_REQUIRED) }
            val api = QuestionApi(WyrHttpClient.create(BASE_URL, storeHolding(session("a")), engine, upgrade = upgrade))

            val failure = assertFailsWith<ApiException> { api.page() }

            assertEquals(ErrorCode.UPGRADE_REQUIRED, failure.code)
            assertEquals(true, upgrade.required.value)
        }

    @Test
    fun `no other failure raises the upgrade signal`() =
        runTest {
            val upgrade = UpgradeSignal()
            val engine = MockEngine { respondErrorDto(HttpStatusCode.Conflict, ErrorCode.ALREADY_VOTED) }
            val api = QuestionApi(WyrHttpClient.create(BASE_URL, storeHolding(session("a")), engine, upgrade = upgrade))

            assertFailsWith<ApiException> { api.page() }

            assertEquals(false, upgrade.required.value)
        }

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
    fun `a 429 carries the wait its Retry-After names`() =
        runTest {
            val limited = MockEngine { rateLimited(retryAfter = " 42 ") }

            val failure = assertFailsWith<ApiException> { questionApi(limited).page() }

            assertEquals(ErrorCode.RATE_LIMITED, failure.code)
            assertEquals(42.seconds, failure.retryAfter)
        }

    @Test
    fun `a response that names no wait in whole seconds carries none`() =
        runTest {
            listOf(null, "Wed, 21 Oct 2026 07:28:00 GMT", "soon", "-1", "1.5").forEach { header ->
                val limited = MockEngine { rateLimited(retryAfter = header) }

                val failure = assertFailsWith<ApiException>("$header") { questionApi(limited).page() }

                assertNull(failure.retryAfter, "$header")
            }
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

    /** A 429 as the server's rate limiter answers one, with [retryAfter] as its header when there is one. */
    private fun MockRequestHandleScope.rateLimited(retryAfter: String?): HttpResponseData =
        respond(
            WyrJson.encodeToString(ErrorDto(message = "too many requests", code = ErrorCode.RATE_LIMITED)),
            HttpStatusCode.TooManyRequests,
            headers {
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                retryAfter?.let { append(HttpHeaders.RetryAfter, it) }
            },
        )

    private fun questionApi(engine: MockEngine): QuestionApi =
        QuestionApi(WyrHttpClient.create(BASE_URL, storeHolding(session("a")), engine))
}
