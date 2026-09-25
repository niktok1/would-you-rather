package io.ntole.wyr.core.data.reaction

import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.data.BASE_URL
import io.ntole.wyr.core.data.FakeServer
import io.ntole.wyr.core.data.session
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.storeHolding
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.reaction.QuestionReactions
import io.ntole.wyr.core.domain.reaction.Reaction
import io.ntole.wyr.core.domain.reaction.SetReaction
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.api.AuthApi
import io.ntole.wyr.core.network.api.ReactionApi
import io.ntole.wyr.core.reaction.ReactionRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import io.ntole.wyr.core.reaction.Reaction as WireReaction

/** Reacting through the real client, session recovery included, against [FakeServer]. */
class DefaultReactionRepositoryTest {
    private val server = FakeServer()

    @Test
    fun `a first launch's like goes out once with the session just minted`() =
        runTest {
            val setReaction = setReactionOver(storeHolding(null))

            val reactions = setReaction("q1", Reaction.LIKE)

            // FakeServer's answer, decoded through the real client and mapped.
            assertEquals(held(likes = 1, mine = Reaction.LIKE), reactions)
            // Not refused and then recovered: the session was ensured before the reaction was sent.
            assertEquals(requestsSentAs("guest1" to WireReaction.LIKE), server.reactionsSentAs)
        }

    @Test
    fun `a dislike on a dead session is sent again as a fresh guest`() =
        runTest {
            // The server has never heard of "a": the state after a dev server restarts.
            val store = storeHolding(session("a"))

            val reactions = repositoryOver(store).setReaction("q1", Reaction.DISLIKE)

            assertEquals(held(dislikes = 1, mine = Reaction.DISLIKE), reactions, "the guest's dislike")
            assertEquals(1, server.guestsMinted)
            assertEquals("guest1", store.read()?.playerId)
            // The same request both times: a resend asks for the reaction, never a toggle.
            assertEquals(
                requestsSentAs("a" to WireReaction.DISLIKE, "guest1" to WireReaction.DISLIKE),
                server.reactionsSentAs,
            )
        }

    @Test
    fun `a like whose answer was lost is asked for again and held once`() =
        runTest {
            val setReaction = setReactionOver(storeHolding(null))
            server.reactionAnswersToLose = 1

            val lost = assertFailsWith<WyrException> { setReaction("q1", Reaction.LIKE) }
            val reactions = setReaction("q1", Reaction.LIKE)

            assertEquals(DomainError.NETWORK, lost.error)
            // The server set the like the first time, and the second asked for what already held.
            assertEquals(held(likes = 1, mine = Reaction.LIKE), reactions)
            assertEquals(
                listOf(WireReaction.LIKE, WireReaction.LIKE),
                server.reactionsSentAs.map { it.second.reaction },
            )
            assertEquals(1, server.guestsMinted, "a lost answer is no dead session")
        }

    @Test
    fun `a dislike replaces a like and taking it back leaves neither`() =
        runTest {
            val setReaction = setReactionOver(storeHolding(null))
            setReaction("q1", Reaction.LIKE)

            val disliked = setReaction("q1", Reaction.DISLIKE)
            val none = setReaction("q1", Reaction.NONE)
            val again = setReaction("q1", Reaction.NONE)

            assertEquals(held(dislikes = 1, mine = Reaction.DISLIKE), disliked)
            assertEquals(held(), none)
            assertEquals(held(), again, "asking again changes nothing")
            assertEquals(
                listOf(WireReaction.LIKE, WireReaction.DISLIKE, WireReaction.NONE, WireReaction.NONE),
                server.reactionsSentAs.map { it.second.reaction },
            )
        }

    @Test
    fun `recovery is attempted only once`() =
        runTest {
            server.refuseReactionsWith = HttpStatusCode.Unauthorized to ErrorCode.UNAUTHORIZED

            val failure =
                assertFailsWith<WyrException> {
                    repositoryOver(storeHolding(session("a"))).setReaction("q1", Reaction.LIKE)
                }

            assertEquals(DomainError.UNAUTHORIZED, failure.error)
            assertEquals(1, server.guestsMinted)
        }

    @Test
    fun `a reaction to a question no player is served is QUESTION_NOT_FOUND and leaves the session alone`() =
        runTest {
            server.refuseReactionsWith = HttpStatusCode.NotFound to ErrorCode.QUESTION_NOT_FOUND
            val store = storeHolding(session("a"))

            val failure = assertFailsWith<WyrException> { repositoryOver(store).setReaction("pending", Reaction.LIKE) }

            assertEquals(DomainError.QUESTION_NOT_FOUND, failure.error)
            assertEquals(1, server.reactionsSentAs.size)
            assertEquals(0, server.guestsMinted)
            assertEquals(session("a"), store.read())
        }

    /** A reaction to q1 as each player sent it, asking for the reaction beside them. */
    private fun requestsSentAs(vararg reactions: Pair<String, WireReaction>): List<Pair<String?, ReactionRequest>> =
        reactions.map { (player, reaction) -> "Bearer access-$player" to ReactionRequest("q1", reaction) }

    /** Where q1's reactions stand with these counts, the player's own [mine] among them. */
    private fun held(
        likes: Int = 0,
        dislikes: Int = 0,
        mine: Reaction = Reaction.NONE,
    ) = QuestionReactions(questionId = "q1", likeCount = likes, dislikeCount = dislikes, myReaction = mine)

    private fun repositoryOver(store: SessionStore): DefaultReactionRepository {
        val client = WyrHttpClient.create(BASE_URL, store, server.engine)
        return DefaultReactionRepository(ReactionApi(client), DefaultSessionRepository(AuthApi(client), store))
    }

    private fun setReactionOver(store: SessionStore): SetReaction {
        val client = WyrHttpClient.create(BASE_URL, store, server.engine)
        val sessions = DefaultSessionRepository(AuthApi(client), store)
        return SetReaction(DefaultReactionRepository(ReactionApi(client), sessions), sessions)
    }
}
