package io.ntole.wyr.core.data.session

import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.data.BASE_URL
import io.ntole.wyr.core.data.VOTE_RESULT_JSON
import io.ntole.wyr.core.data.respondErrorDto
import io.ntole.wyr.core.data.respondJson
import io.ntole.wyr.core.data.session
import io.ntole.wyr.core.data.storeHolding
import io.ntole.wyr.core.data.vote.DefaultVoteRepository
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.WyrJson
import io.ntole.wyr.core.network.api.AuthApi
import io.ntole.wyr.core.network.api.VoteApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two clients over one store: what two browser tabs sharing localStorage are, or two desktop
 * instances sharing JVM preferences. Each has its own client and its own session lock.
 */
class SharedSessionStoreTest {
    private val rotated = session("a").copy(accessToken = "access-a2", refreshToken = "refresh-a2")
    private var guestsMinted = 0
    private var refreshes = 0
    private val bothRefreshing = CompletableDeferred<Unit>()
    private val rotatedSessionInUse = CompletableDeferred<Unit>()

    // "access-a" has expired. Both tabs refresh with "refresh-a" at once; the server rotates it
    // for the first and refuses the second, answering that one only once the first tab is voting
    // with what it got.
    private val engine =
        MockEngine { request ->
            when (request.url.encodedPath) {
                WyrApi.Paths.AUTH_GUEST -> {
                    guestsMinted++
                    respondJson(WyrJson.encodeToString(session("guest$guestsMinted")))
                }

                WyrApi.Paths.AUTH_REFRESH -> {
                    val arrival = ++refreshes
                    if (arrival == 2) bothRefreshing.complete(Unit)
                    bothRefreshing.await()
                    if (arrival == 1) {
                        respondJson(WyrJson.encodeToString(rotated))
                    } else {
                        rotatedSessionInUse.await()
                        respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.INVALID_REFRESH_TOKEN)
                    }
                }

                WyrApi.Paths.VOTES -> {
                    when (val authorization = request.headers[HttpHeaders.Authorization]) {
                        "Bearer access-a" -> {
                            respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
                        }

                        else -> {
                            if (authorization == "Bearer access-a2") rotatedSessionInUse.complete(Unit)
                            respondJson(VOTE_RESULT_JSON)
                        }
                    }
                }

                else -> {
                    error("no route for ${request.url}")
                }
            }
        }

    private val store: SessionStore = storeHolding(session("a"))

    private fun tab(): DefaultVoteRepository {
        val client = WyrHttpClient.create(BASE_URL, store, engine)
        return DefaultVoteRepository(VoteApi(client), DefaultSessionRepository(AuthApi(client), store))
    }

    @Test
    fun `losing a refresh race to another tab keeps the live session`() =
        runTest {
            val first = tab()
            val second = tab()

            awaitAll(async { first.cast("q1", Side.A) }, async { second.cast("q1", Side.A) })

            assertEquals(2, refreshes)
            assertEquals(0, guestsMinted)
            assertEquals(rotated, store.read())
        }
}
