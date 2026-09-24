package io.ntole.wyr.core.data.like

import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.data.BASE_URL
import io.ntole.wyr.core.data.FakeServer
import io.ntole.wyr.core.data.session
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.storeHolding
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.like.QuestionLikes
import io.ntole.wyr.core.domain.like.SetLike
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.like.LikeRequest
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.api.AuthApi
import io.ntole.wyr.core.network.api.LikeApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Liking and unliking through the real client, session recovery included, against [FakeServer]. */
class DefaultLikeRepositoryTest {
    private val server = FakeServer()

    @Test
    fun `a first launch's like goes out once with the session just minted`() =
        runTest {
            val setLike = setLikeOver(storeHolding(null))

            val likes = setLike("q1", liked = true)

            // FakeServer's answer, decoded through the real client and mapped.
            assertEquals(QuestionLikes(questionId = "q1", likeCount = 1, likedByMe = true), likes)
            // Not refused and then recovered: the session was ensured before the like was sent.
            assertEquals(requestsSentAs("guest1" to true), server.likesSentAs)
        }

    @Test
    fun `a like on a dead session is sent again as a fresh guest`() =
        runTest {
            // The server has never heard of "a": the state after a dev server restarts.
            val store = storeHolding(session("a"))

            val likes = repositoryOver(store).setLiked("q1", liked = true)

            assertEquals(QuestionLikes(questionId = "q1", likeCount = 1, likedByMe = true), likes, "the guest's like")
            assertEquals(1, server.guestsMinted)
            assertEquals("guest1", store.read()?.playerId)
            // The same request both times, `liked` included: a resend asks for the like, never a toggle.
            assertEquals(requestsSentAs("a" to true, "guest1" to true), server.likesSentAs)
        }

    @Test
    fun `a like whose answer was lost is asked for again and held once`() =
        runTest {
            val setLike = setLikeOver(storeHolding(null))
            server.likeAnswersToLose = 1

            val lost = assertFailsWith<WyrException> { setLike("q1", liked = true) }
            val likes = setLike("q1", liked = true)

            assertEquals(DomainError.NETWORK, lost.error)
            // The server set the like the first time, and the second asked for what already held.
            assertEquals(QuestionLikes(questionId = "q1", likeCount = 1, likedByMe = true), likes)
            assertEquals(listOf(true, true), server.likesSentAs.map { it.second.liked })
            assertEquals(1, server.guestsMinted, "a lost answer is no dead session")
        }

    @Test
    fun `an unlike takes the like back and asking again changes nothing`() =
        runTest {
            val setLike = setLikeOver(storeHolding(null))
            setLike("q1", liked = true)

            val unliked = setLike("q1", liked = false)
            val again = setLike("q1", liked = false)

            val none = QuestionLikes(questionId = "q1", likeCount = 0, likedByMe = false)
            assertEquals(none, unliked)
            assertEquals(none, again)
            assertEquals(listOf(true, false, false), server.likesSentAs.map { it.second.liked })
        }

    @Test
    fun `recovery is attempted only once`() =
        runTest {
            server.refuseLikesWith = HttpStatusCode.Unauthorized to ErrorCode.UNAUTHORIZED

            val failure =
                assertFailsWith<WyrException> { repositoryOver(storeHolding(session("a"))).setLiked("q1", true) }

            assertEquals(DomainError.UNAUTHORIZED, failure.error)
            assertEquals(1, server.guestsMinted)
        }

    @Test
    fun `a like of a question no player is served is QUESTION_NOT_FOUND and leaves the session alone`() =
        runTest {
            server.refuseLikesWith = HttpStatusCode.NotFound to ErrorCode.QUESTION_NOT_FOUND
            val store = storeHolding(session("a"))

            val failure = assertFailsWith<WyrException> { repositoryOver(store).setLiked("pending", true) }

            assertEquals(DomainError.QUESTION_NOT_FOUND, failure.error)
            assertEquals(1, server.likesSentAs.size)
            assertEquals(0, server.guestsMinted)
            assertEquals(session("a"), store.read())
        }

    /** A like of q1 as each player sent it, asking for the `liked` beside them. */
    private fun requestsSentAs(vararg likes: Pair<String, Boolean>): List<Pair<String?, LikeRequest>> =
        likes.map { (player, liked) -> "Bearer access-$player" to LikeRequest(questionId = "q1", liked = liked) }

    private fun repositoryOver(store: SessionStore): DefaultLikeRepository {
        val client = WyrHttpClient.create(BASE_URL, store, server.engine)
        return DefaultLikeRepository(LikeApi(client), DefaultSessionRepository(AuthApi(client), store))
    }

    private fun setLikeOver(store: SessionStore): SetLike {
        val client = WyrHttpClient.create(BASE_URL, store, server.engine)
        val sessions = DefaultSessionRepository(AuthApi(client), store)
        return SetLike(DefaultLikeRepository(LikeApi(client), sessions), sessions)
    }
}
