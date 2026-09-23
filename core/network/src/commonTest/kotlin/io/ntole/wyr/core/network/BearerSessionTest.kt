package io.ntole.wyr.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.network.api.QuestionApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which credentials a request goes out with, as the session in [SessionStore] changes. */
class BearerSessionTest {
    @Test
    fun `a replaced session is sent on the very next request`() =
        runTest {
            val store = storeHolding(session("a"))
            val engine = MockEngine { respondEmptyPage() }
            val api = QuestionApi(WyrHttpClient.create(BASE_URL, store, engine))

            api.page()
            // What minting a fresh guest does: the data layer writes the new session to the store.
            store.write(session("b"))
            api.page()

            assertEquals(listOf("Bearer access-a", "Bearer access-b"), engine.authorizationHeaders())
        }

    @Test
    fun `a cleared session sends no bearer at all`() =
        runTest {
            val store = storeHolding(session("a"))
            val engine = MockEngine { respondEmptyPage() }
            val api = QuestionApi(WyrHttpClient.create(BASE_URL, store, engine))

            api.page()
            store.clear()
            api.page()

            assertEquals(listOf("Bearer access-a", null), engine.authorizationHeaders())
        }

    @Test
    fun `an expired access token is refreshed, stored, and the call retried with it`() =
        runTest {
            val store = storeHolding(session("a"))
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath == WyrApi.Paths.AUTH_REFRESH -> respondSession(session("a2"))
                        request.headers[HttpHeaders.Authorization] == "Bearer access-a2" -> respondEmptyPage()
                        else -> respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
                    }
                }

            QuestionApi(WyrHttpClient.create(BASE_URL, store, engine)).page()

            assertEquals(session("a2"), store.read())
            // The refresh itself carries no bearer: its credential is the refresh token in the body.
            assertEquals(listOf("Bearer access-a", null, "Bearer access-a2"), engine.authorizationHeaders())
        }

    @Test
    fun `calls rejected together share one refresh`() =
        runTest {
            // Refresh tokens rotate on every use (CLAUDE.md §8a), so each extra refresh is a
            // needless rotation — and a lost race over one would kill the session.
            val store = storeHolding(session("a"))
            val arrivals = Mutex()
            var rejectedSoFar = 0
            val bothRejected = CompletableDeferred<Unit>()
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath == WyrApi.Paths.AUTH_REFRESH -> {
                            respondSession(session("a2"))
                        }

                        request.headers[HttpHeaders.Authorization] == "Bearer access-a2" -> {
                            respondEmptyPage()
                        }

                        else -> {
                            // Hold the first rejection until the second call has gone out as "a" too.
                            arrivals.withLock { if (++rejectedSoFar == 2) bothRejected.complete(Unit) }
                            bothRejected.await()
                            respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
                        }
                    }
                }
            val api = QuestionApi(WyrHttpClient.create(BASE_URL, store, engine))

            awaitAll(async { api.page() }, async { api.page() })

            assertEquals(1, engine.requestHistory.count { it.url.encodedPath == WyrApi.Paths.AUTH_REFRESH })
            assertEquals(session("a2"), store.read())
        }

    @Test
    fun `a refresh outlives the cancelled call that started it`() =
        runTest {
            // The server rotates the refresh token the moment it answers. Abandoning the refresh
            // then would leave a dead refresh token in the store.
            val store = storeHolding(session("a"))
            val refreshArrived = CompletableDeferred<Unit>()
            val answerRefresh = CompletableDeferred<Unit>()
            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        WyrApi.Paths.AUTH_REFRESH -> {
                            refreshArrived.complete(Unit)
                            answerRefresh.await()
                            respondSession(session("a2"))
                        }

                        else -> {
                            respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
                        }
                    }
                }
            val api = QuestionApi(WyrHttpClient.create(BASE_URL, store, engine))

            val call = launch { api.page() }
            refreshArrived.await()
            call.cancel()
            answerRefresh.complete(Unit)
            call.join()

            assertEquals(session("a2"), store.read())
        }

    @Test
    fun `a session replaced during a refresh is not overwritten by it`() =
        runTest {
            val store = storeHolding(session("a"))
            val refreshArrived = CompletableDeferred<Unit>()
            val answerRefresh = CompletableDeferred<Unit>()
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath == WyrApi.Paths.AUTH_REFRESH -> {
                            refreshArrived.complete(Unit)
                            answerRefresh.await()
                            respondSession(session("a2"))
                        }

                        request.headers[HttpHeaders.Authorization] == "Bearer access-b" -> {
                            respondEmptyPage()
                        }

                        else -> {
                            respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
                        }
                    }
                }
            val api = QuestionApi(WyrHttpClient.create(BASE_URL, store, engine))

            val call = async { api.page() }
            refreshArrived.await()
            // A deliberate session change lands while the refresh for "a" is still in flight.
            store.write(session("b"))
            answerRefresh.complete(Unit)
            call.await()

            assertEquals(session("b"), store.read())
            assertEquals(listOf("Bearer access-a", null, "Bearer access-b"), engine.authorizationHeaders())
        }

    private fun MockEngine.authorizationHeaders(): List<String?> =
        requestHistory.map { it.headers[HttpHeaders.Authorization] }
}
