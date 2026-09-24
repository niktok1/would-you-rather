package io.ntole.wyr.core.data.mapper

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.data.BASE_URL
import io.ntole.wyr.core.data.respondErrorDto
import io.ntole.wyr.core.data.respondJson
import io.ntole.wyr.core.data.session
import io.ntole.wyr.core.data.storeHolding
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.WyrJson
import io.ntole.wyr.core.network.api.QuestionApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * runApi against what the real client configuration actually throws, rather than hand-built
 * exceptions — the gap between the two is where every failure used to collapse into UNKNOWN.
 */
class RunApiOverHttpTest {
    @Test
    fun `a request that never reached the server is NETWORK`() =
        runTest {
            val offline = MockEngine { throw ConnectTimeoutException("connect timed out") }

            assertEquals(DomainError.NETWORK, errorFrom(offline))
        }

    @Test
    fun `a request the server never answered in time is NETWORK`() =
        runTest {
            // On the test's scheduler, so the client's request timeout runs out in virtual time.
            val silent =
                MockEngine(
                    MockEngineConfig().apply {
                        dispatcher = StandardTestDispatcher(testScheduler)
                        addHandler {
                            delay(1.hours)
                            respondJson("""{"questions":[]}""")
                        }
                    },
                )

            assertEquals(DomainError.NETWORK, errorFrom(silent))
        }

    @Test
    fun `a proxy's HTML 503 page is SERVER`() =
        runTest {
            val proxyPage =
                MockEngine {
                    respond(
                        "<html><body>503 Service Unavailable</body></html>",
                        HttpStatusCode.ServiceUnavailable,
                        headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()),
                    )
                }

            assertEquals(DomainError.SERVER, errorFrom(proxyPage))
        }

    @Test
    fun `a dead refresh token is UNAUTHORIZED`() =
        runTest {
            // This is the code that triggers session recovery; it used to arrive as UNKNOWN.
            val deadSession =
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

            assertEquals(DomainError.UNAUTHORIZED, errorFrom(deadSession))
        }

    @Test
    fun `the server's 429 is RATE_LIMITED and is sent once`() =
        runTest {
            var sent = 0
            val limited =
                MockEngine {
                    sent++
                    respond(
                        WyrJson.encodeToString(
                            ErrorDto(message = "too many requests; retry in 42 s", code = ErrorCode.RATE_LIMITED),
                        ),
                        HttpStatusCode.TooManyRequests,
                        headersOf(
                            HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                            HttpHeaders.RetryAfter to listOf("42"),
                        ),
                    )
                }

            val failure = failureFrom(limited)
            assertEquals(DomainError.RATE_LIMITED, failure.error)
            assertEquals(42.seconds, failure.retryAfter, "the wait the server named reaches the caller")
            assertEquals(1, sent, "no retry, and no refresh: a 429 says nothing about the session")
        }

    @Test
    fun `a proxy's own 429 page is RATE_LIMITED too`() =
        runTest {
            // As Cloudflare, in front of Render, would answer one: HTML, no ErrorDto.
            val proxyLimit =
                MockEngine {
                    respond(
                        "<html><body>429 Too Many Requests</body></html>",
                        HttpStatusCode.TooManyRequests,
                        headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()),
                    )
                }

            assertEquals(DomainError.RATE_LIMITED, errorFrom(proxyLimit))
        }

    @Test
    fun `a success body this build cannot read is SERVER`() =
        runTest {
            // Contract drift, such as the server dropping a field this build still requires.
            // Nothing is wrong with the connection, and retrying will not help.
            val driftedServer =
                MockEngine {
                    respond(
                        """{"unexpected":true}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }

            assertEquals(DomainError.SERVER, errorFrom(driftedServer))
        }

    @Test
    fun `a success page that is not JSON at all stays NETWORK`() =
        runTest {
            // What a captive portal's login page looks like to the app: the network is in the way.
            val captivePortal =
                MockEngine {
                    respond(
                        "<html><body>Sign in to the Wi-Fi</body></html>",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()),
                    )
                }

            assertEquals(DomainError.NETWORK, errorFrom(captivePortal))
        }

    private suspend fun errorFrom(engine: MockEngine): DomainError = failureFrom(engine).error

    private suspend fun failureFrom(engine: MockEngine): WyrException {
        val api = QuestionApi(WyrHttpClient.create(BASE_URL, storeHolding(session("a")), engine))
        return assertFailsWith<WyrException> { runApi { api.page() } }
    }
}
