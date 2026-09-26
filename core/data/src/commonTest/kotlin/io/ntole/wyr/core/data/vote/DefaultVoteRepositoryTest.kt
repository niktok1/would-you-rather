package io.ntole.wyr.core.data.vote

import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.data.BASE_URL
import io.ntole.wyr.core.data.FakeServer
import io.ntole.wyr.core.data.session
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.storeHolding
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.vote.AttemptId
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.api.AuthApi
import io.ntole.wyr.core.network.api.VoteApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DefaultVoteRepositoryTest {
    private val server = FakeServer()

    // The server has never heard of "a": its session is dead, refresh token included.
    private val store: SessionStore = storeHolding(session("a"))

    private val votes: DefaultVoteRepository =
        WyrHttpClient.create(BASE_URL, store, server.engine).let { client ->
            DefaultVoteRepository(VoteApi(client), DefaultSessionRepository(AuthApi(client), store))
        }

    @Test
    fun `a vote on a dead session is retried as a fresh guest`() =
        runTest {
            val outcome = votes.cast("q1", Side.A, AttemptId.random())

            assertEquals(Side.A, outcome.yourSide, "the fresh guest's vote, as the server answered it")
            assertEquals(1, server.guestsMinted)
            assertEquals("guest1", store.read()?.playerId)
            assertEquals(listOf<String?>("Bearer access-a", "Bearer access-guest1"), server.votesSentAs)
        }

    @Test
    fun `the caller's attempt is sent and resent unchanged by the session recovery`() =
        runTest {
            val answer = AttemptId.random()
            val nextAnswer = AttemptId.random()

            votes.cast("q1", Side.A, answer)
            // The caller retrying the same answer, then answering again.
            votes.cast("q1", Side.A, answer)
            votes.cast("q1", Side.A, nextAnswer)

            // The first cast went out twice, around the session recovery. That retry is the same
            // answer, so it carries the same attempt as the caller's own retry does.
            assertEquals(listOf(answer, answer, answer, nextAnswer).map { it.value }, server.voteAttempts)
        }

    /** How long the answer took goes with it, and with its retry around a recovered session unchanged. */
    @Test
    fun `the answer's time is sent and resent unchanged by the session recovery`() =
        runTest {
            votes.cast("q1", Side.B, AttemptId.random(), answerMillis = 2_345)
            votes.cast("q1", Side.B, AttemptId.random())

            assertEquals(listOf<Long?>(2_345, 2_345, null), server.voteAnswerMillis)
        }

    @Test
    fun `a random attempt fits the wire's limit`() {
        val attemptId = AttemptId.random().value

        assertTrue(attemptId.isNotBlank() && attemptId.length <= WyrApi.Limits.MAX_ATTEMPT_ID_LENGTH, attemptId)
    }

    @Test
    fun `recovery is attempted only once`() =
        runTest {
            server.refuseVotesWith = HttpStatusCode.Unauthorized to ErrorCode.UNAUTHORIZED

            val failure = assertFailsWith<WyrException> { votes.cast("q1", Side.A, AttemptId.random()) }

            assertEquals(DomainError.UNAUTHORIZED, failure.error)
            assertEquals(1, server.guestsMinted)
        }

    @Test
    fun `a rate-limited vote is sent once and leaves the session alone`() =
        runTest {
            server.refuseVotesWith = HttpStatusCode.TooManyRequests to ErrorCode.RATE_LIMITED

            val failure = assertFailsWith<WyrException> { votes.cast("q1", Side.A, AttemptId.random()) }

            assertEquals(DomainError.RATE_LIMITED, failure.error)
            assertEquals(1, server.votesSentAs.size, "nothing resends a refused vote")
            assertEquals(0, server.guestsMinted)
            assertEquals(session("a"), store.read())
        }

    @Test
    fun `a rate-limited refresh keeps the session and mints no guest in its place`() =
        runTest {
            // The vote is refused as a dead access token, and the refresh it sets off is refused for
            // the rate. That is no verdict on the session: minting a guest would orphan the player.
            server.refuseRefreshesWith = HttpStatusCode.TooManyRequests to ErrorCode.RATE_LIMITED

            val failure = assertFailsWith<WyrException> { votes.cast("q1", Side.A, AttemptId.random()) }

            assertEquals(DomainError.RATE_LIMITED, failure.error)
            assertEquals(1, server.refreshesSent)
            assertEquals(1, server.votesSentAs.size, "the vote is not retried")
            assertEquals(0, server.guestsMinted)
            assertEquals(session("a"), store.read(), "the refresh token the server did not rotate is kept")
        }

    @Test
    fun `any other failure leaves the session alone`() =
        runTest {
            server.refuseVotesWith = HttpStatusCode.Conflict to ErrorCode.ALREADY_VOTED

            val failure = assertFailsWith<WyrException> { votes.cast("q1", Side.A, AttemptId.random()) }

            assertEquals(DomainError.ALREADY_VOTED, failure.error)
            assertEquals(0, server.guestsMinted)
            assertEquals(session("a"), store.read())
        }
}
