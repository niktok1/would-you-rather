package io.ntole.wyr.core.domain.like

import io.ntole.wyr.core.domain.session.SessionRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SetLikeTest {
    private val calls = mutableListOf<String>()

    @Test
    fun `the session is ensured before the like is set`() =
        runTest {
            val setLike = SetLike(RecordingLikes(calls), RecordingSessions(calls))

            assertEquals(QuestionLikes("q1", likeCount = 1, likedByMe = true), setLike("q1", liked = true))
            assertEquals(listOf("ensure", "setLiked q1 true"), calls)
        }

    @Test
    fun `an unlike is passed on as one rather than as a toggle`() =
        runTest {
            val setLike = SetLike(RecordingLikes(calls), RecordingSessions(calls))

            assertEquals(QuestionLikes("q1", likeCount = 0, likedByMe = false), setLike("q1", liked = false))
            assertEquals(listOf("ensure", "setLiked q1 false"), calls)
        }

    /** Answers with the like as asked for, as the server holds it once set. */
    private class RecordingLikes(
        private val calls: MutableList<String>,
    ) : LikeRepository {
        override suspend fun setLiked(
            questionId: String,
            liked: Boolean,
        ): QuestionLikes {
            calls += "setLiked $questionId $liked"
            return QuestionLikes(questionId, likeCount = if (liked) 1 else 0, likedByMe = liked)
        }
    }

    private class RecordingSessions(
        private val calls: MutableList<String>,
    ) : SessionRepository {
        override suspend fun ensure(): String {
            calls += "ensure"
            return "p1"
        }

        override suspend fun currentPlayerId(): String = "p1"

        override suspend fun clear() = Unit

        override suspend fun clearKeepingSecret() = Unit
    }
}
