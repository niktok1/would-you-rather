package io.ntole.wyr.core.data.session

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.RefreshRequest
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.data.BASE_URL
import io.ntole.wyr.core.data.VOTE_RESULT_JSON
import io.ntole.wyr.core.data.respondErrorDto
import io.ntole.wyr.core.data.respondJson
import io.ntole.wyr.core.data.session
import io.ntole.wyr.core.data.storeHolding
import io.ntole.wyr.core.data.vote.DefaultVoteRepository
import io.ntole.wyr.core.domain.vote.AttemptId
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    // for the first and refuses the second, as it does with its grace window off or past a bound, or
    // when "refresh-a" was already the previous one (CLAUDE.md §8a), answering that one only once the
    // first tab is voting with what it got.
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

    private fun tab(engine: MockEngine = this.engine): DefaultVoteRepository {
        val client = WyrHttpClient.create(BASE_URL, store, engine)
        return DefaultVoteRepository(VoteApi(client), DefaultSessionRepository(AuthApi(client), store))
    }

    @Test
    fun `losing a refresh race to another tab keeps the live session`() =
        runTest {
            val first = tab()
            val second = tab()

            awaitAll(
                async { first.cast("q1", Side.A, AttemptId.random()) },
                async { second.cast("q1", Side.A, AttemptId.random()) },
            )

            assertEquals(2, refreshes)
            assertEquals(0, guestsMinted)
            assertEquals(rotated, store.read())
        }

    /**
     * The same race against a server with its grace window (CLAUDE.md §8a), which lets both refreshes
     * through, in the order they arrive: the second spends "refresh-a" as the token the first
     * displaced, which leaves the first's new token only the previous one, good for one refresh: of
     * the two tabs' next refreshes at once, one would be refused. The first tab hears back first and
     * stores that one, as the tab whose refresh reached the server first usually does. The second tab,
     * finding it stored, refreshes once more as it, so the store ends on the player's current token,
     * with which two refreshes at once both go through.
     */
    @Test
    fun `when the server lets both tabs' refreshes through, the store ends on the player's current token`() =
        runTest {
            val server = GraceServer()
            val serverLock = Mutex()
            val bothRotated = CompletableDeferred<Unit>()
            val firstAnswerInUse = CompletableDeferred<Unit>()
            var arrivals = 0
            val graceEngine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        WyrApi.Paths.AUTH_GUEST -> {
                            guestsMinted++
                            respondJson(WyrJson.encodeToString(session("guest$guestsMinted")))
                        }

                        WyrApi.Paths.AUTH_REFRESH -> {
                            val body = request.body.toByteArray().decodeToString()
                            val presented = WyrJson.decodeFromString<RefreshRequest>(body).refreshToken
                            // One step, as the server's row lock makes it: MockEngine answers on
                            // threads of its own, and the second arrival rotating first would be
                            // answered "refresh-a2" and then wait for itself.
                            val (arrival, answer) = serverLock.withLock { ++arrivals to server.refresh(presented) }
                            // Both refreshes are run before either is answered, and the second is
                            // answered only once the first tab has stored and used what it got.
                            if (arrival == 1) bothRotated.await()
                            if (arrival == 2) {
                                bothRotated.complete(Unit)
                                firstAnswerInUse.await()
                            }
                            if (answer == null) {
                                respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.INVALID_REFRESH_TOKEN)
                            } else {
                                respondJson(WyrJson.encodeToString(answer))
                            }
                        }

                        WyrApi.Paths.VOTES -> {
                            // An access token outlives the refresh token it came with, as a signed one does.
                            when (val authorization = request.headers[HttpHeaders.Authorization]) {
                                "Bearer access-a" -> {
                                    respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
                                }

                                else -> {
                                    if (authorization == "Bearer access-a2") firstAnswerInUse.complete(Unit)
                                    respondJson(VOTE_RESULT_JSON)
                                }
                            }
                        }

                        else -> {
                            error("no route for ${request.url}")
                        }
                    }
                }
            val first = tab(graceEngine)
            val second = tab(graceEngine)

            awaitAll(
                async { first.cast("q1", Side.A, AttemptId.random()) },
                async { second.cast("q1", Side.A, AttemptId.random()) },
            )

            assertEquals(3, arrivals, "both tabs' refreshes, then the second's once more as what the first stored")
            assertEquals(0, guestsMinted)
            assertEquals(server.current, store.read()?.refreshToken, "the store holds the player's current token")
        }

    /**
     * Player "a"'s refresh tokens as the server keeps them with its grace window: the current one, and
     * the one the last rotation displaced, which works once more. The grace has no time bound by
     * default, so time is left out.
     */
    private class GraceServer {
        var current = "refresh-a"
            private set
        private var previous: String? = null
        private var rotations = 1

        fun refresh(presented: String): SessionDto? {
            if (presented != current && presented != previous) return null
            previous = current
            rotations++
            current = "refresh-a$rotations"
            return session("a").copy(accessToken = "access-a$rotations", refreshToken = current)
        }
    }
}
