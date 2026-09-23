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
import kotlin.test.assertNotEquals
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
            val outcome = votes.cast("q1", Side.A)

            assertEquals("q1", outcome.questionId)
            assertEquals(1, server.guestsMinted)
            assertEquals("guest1", store.read()?.playerId)
            assertEquals(listOf<String?>("Bearer access-a", "Bearer access-guest1"), server.votesSentAs)
        }

    @Test
    fun `every cast is an answer with an attempt id of its own`() =
        runTest {
            votes.cast("q1", Side.A)
            votes.cast("q1", Side.A)

            // The first went out twice, around the session recovery, and that retry is the same
            // answer. The second cast is another answer.
            val (first, retried, second) = server.voteAttempts
            assertEquals(first, retried)
            assertNotEquals(first, second)
            assertTrue(second.isNotBlank() && second.length <= WyrApi.Limits.MAX_ATTEMPT_ID_LENGTH, second)
        }

    @Test
    fun `recovery is attempted only once`() =
        runTest {
            server.refuseVotesWith = HttpStatusCode.Unauthorized to ErrorCode.UNAUTHORIZED

            val failure = assertFailsWith<WyrException> { votes.cast("q1", Side.A) }

            assertEquals(DomainError.UNAUTHORIZED, failure.error)
            assertEquals(1, server.guestsMinted)
        }

    @Test
    fun `any other failure leaves the session alone`() =
        runTest {
            server.refuseVotesWith = HttpStatusCode.Conflict to ErrorCode.ALREADY_VOTED

            val failure = assertFailsWith<WyrException> { votes.cast("q1", Side.A) }

            assertEquals(DomainError.ALREADY_VOTED, failure.error)
            assertEquals(0, server.guestsMinted)
            assertEquals(session("a"), store.read())
        }
}
