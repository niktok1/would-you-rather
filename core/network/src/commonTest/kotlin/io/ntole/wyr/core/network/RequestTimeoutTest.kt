package io.ntole.wyr.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.network.WyrHttpClient.CONNECT_TIMEOUT
import io.ntole.wyr.core.network.WyrHttpClient.REFRESH_TIMEOUT
import io.ntole.wyr.core.network.WyrHttpClient.REQUEST_TIMEOUT
import io.ntole.wyr.core.network.api.AuthApi
import io.ntole.wyr.core.network.api.QuestionApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * How long a call may take. Every engine here runs on the test's scheduler, so the server's delays
 * and HttpTimeout's own timer both run in virtual time, and [currentTime] says when a call gave up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestTimeoutTest {
    @Test
    fun `a call the server never answers fails at the request timeout`() =
        runTest {
            val silent =
                engine {
                    delay(1.hours)
                    respondEmptyPage()
                }

            // Not an ApiException: nothing answered, and runApi calls this NETWORK.
            assertFailsWith<HttpRequestTimeoutException> { questionApi(silent).page() }
            assertEquals(REQUEST_TIMEOUT.inWholeMilliseconds, currentTime)
        }

    @Test
    fun `an answer as slow as a cold start still arrives`() =
        runTest {
            // Render's free tier can take most of a minute to wake (CLAUDE.md §8), and the call that
            // woke it should get its answer.
            val coldStart =
                engine {
                    delay(50.seconds)
                    respondEmptyPage()
                }

            questionApi(coldStart).page()
        }

    @Test
    fun `every call tells the engine its connect and socket timeouts`() =
        runTest {
            // The engines enforce both, and each has its own shorter default: OkHttp waits 10 s for
            // a packet, CIO 5 s for a connection. A retry after a refresh must carry them too.
            val engine = engine(expiredAccessToken())

            questionApi(engine).page()

            val calls = engine.requestHistory.filter { it.url.encodedPath != WyrApi.Paths.AUTH_REFRESH }
            assertEquals(2, calls.size)
            calls.forEach { call -> assertEquals(ordinaryTimeouts, call.timeouts()) }
        }

    @Test
    fun `a refresh gets the longer timeout and keeps the ordinary connect timeout`() =
        runTest {
            val engine = engine(expiredAccessToken())

            questionApi(engine).page()

            val refresh = engine.requestHistory.single { it.url.encodedPath == WyrApi.Paths.AUTH_REFRESH }
            assertEquals(refreshTimeouts, refresh.timeouts())
        }

    @Test
    fun `a refresh slower than the request timeout still lands`() =
        runTest {
            // The server rotates the refresh token as it answers (CLAUDE.md §8a). Cut short at the
            // ordinary bound, this refresh would leave the rotated-out token stored, which the server
            // takes once more at most, and not at all if it was already the previous one.
            val store = storeHolding(session("a"))
            val engine =
                engine(
                    expiredAccessToken {
                        delay(REQUEST_TIMEOUT + 30.seconds)
                    },
                )

            // The call that asked for it has outlived its own bound by then, so it still fails, as
            // a timeout: NETWORK, never a cancellation it did not ask for.
            assertFailsWith<HttpRequestTimeoutException> { QuestionApi(client(store, engine)).page() }
            assertEquals(session("a2"), store.read())
        }

    @Test
    fun `a refresh that never ends is still bounded`() =
        runTest {
            // Every call rejected meanwhile waits behind the refresh, uncancellably, so without a
            // bound a dead connection would hold them all until the app is killed.
            val store = storeHolding(session("a"))
            val engine =
                engine(
                    expiredAccessToken {
                        delay(1.hours)
                    },
                )

            assertFailsWith<HttpRequestTimeoutException> { QuestionApi(client(store, engine)).page() }
            assertEquals(REFRESH_TIMEOUT.inWholeMilliseconds, currentTime)
            assertEquals(session("a"), store.read())
        }

    @Test
    fun `AuthApi's refresh gets the longer timeout too`() =
        runTest {
            val engine = engine { respondSession(session("a2")) }

            AuthApi(client(storeHolding(null), engine)).refresh("refresh-a")

            assertEquals(refreshTimeouts, engine.requestHistory.single().timeouts())
        }

    private val ordinaryTimeouts =
        HttpTimeoutConfig(
            requestTimeoutMillis = REQUEST_TIMEOUT.inWholeMilliseconds,
            connectTimeoutMillis = CONNECT_TIMEOUT.inWholeMilliseconds,
            socketTimeoutMillis = REQUEST_TIMEOUT.inWholeMilliseconds,
        )

    private val refreshTimeouts =
        HttpTimeoutConfig(
            requestTimeoutMillis = REFRESH_TIMEOUT.inWholeMilliseconds,
            connectTimeoutMillis = CONNECT_TIMEOUT.inWholeMilliseconds,
            socketTimeoutMillis = REFRESH_TIMEOUT.inWholeMilliseconds,
        )

    private fun HttpRequestData.timeouts(): HttpTimeoutConfig? = getCapabilityOrNull(HttpTimeoutCapability)

    /** Rejects the stored access token, so a call goes 401, refresh, retry. [beforeRefresh] delays the refresh. */
    private fun expiredAccessToken(beforeRefresh: suspend () -> Unit = {}): MockRequestHandler =
        { request ->
            when {
                request.url.encodedPath == WyrApi.Paths.AUTH_REFRESH -> {
                    beforeRefresh()
                    respondSession(session("a2"))
                }

                request.headers[HttpHeaders.Authorization] == "Bearer access-a2" -> {
                    respondEmptyPage()
                }

                else -> {
                    respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
                }
            }
        }

    /** A [MockEngine] on the test's scheduler, so its delays and HttpTimeout's run in virtual time. */
    private fun TestScope.engine(handler: MockRequestHandler): MockEngine =
        MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler(handler)
            },
        )

    private fun client(
        store: SessionStore,
        engine: MockEngine,
    ) = WyrHttpClient.create(BASE_URL, store, engine)

    private fun questionApi(engine: MockEngine): QuestionApi = QuestionApi(client(storeHolding(session("a")), engine))
}
