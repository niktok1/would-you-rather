package io.ntole.wyr.core.domain.reaction

import io.ntole.wyr.core.domain.session.SessionRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SetReactionTest {
    private val calls = mutableListOf<String>()

    @Test
    fun `the session is ensured before the reaction is set`() =
        runTest {
            val setReaction = SetReaction(RecordingReactions(calls), RecordingSessions(calls))

            assertEquals(held("q1", Reaction.LIKE), setReaction("q1", Reaction.LIKE))
            assertEquals(listOf("ensure", "setReaction q1 LIKE"), calls)
        }

    @Test
    fun `taking a reaction back is passed on as one rather than as a toggle`() =
        runTest {
            val setReaction = SetReaction(RecordingReactions(calls), RecordingSessions(calls))

            assertEquals(held("q1", Reaction.NONE), setReaction("q1", Reaction.NONE))
            assertEquals(listOf("ensure", "setReaction q1 NONE"), calls)
        }

    /** Answers with the reaction as asked for, as the server holds it once set. */
    private class RecordingReactions(
        private val calls: MutableList<String>,
    ) : ReactionRepository {
        override suspend fun setReaction(
            questionId: String,
            reaction: Reaction,
        ): QuestionReactions {
            calls += "setReaction $questionId $reaction"
            return held(questionId, reaction)
        }
    }

    private class RecordingSessions(
        private val calls: MutableList<String>,
    ) : SessionRepository {
        override suspend fun ensure(): String {
            calls += "ensure"
            return "p1"
        }
    }

    private companion object {
        /** A question only this player has reacted to, with [reaction]. */
        fun held(
            questionId: String,
            reaction: Reaction,
        ) = QuestionReactions(
            questionId,
            likeCount = if (reaction == Reaction.LIKE) 1 else 0,
            dislikeCount = if (reaction == Reaction.DISLIKE) 1 else 0,
            myReaction = reaction,
        )
    }
}
