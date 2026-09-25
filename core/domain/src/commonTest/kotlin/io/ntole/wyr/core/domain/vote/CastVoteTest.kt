package io.ntole.wyr.core.domain.vote

import io.ntole.wyr.core.domain.session.SessionRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CastVoteTest {
    private val calls = mutableListOf<String>()

    private val castVote = CastVote(RecordingVotes(calls), RecordingSessions(calls))

    @Test
    fun `the session is ensured and then the caller's attempt is sent unchanged`() =
        runTest {
            val attempt = AttemptId.random()

            castVote("q1", Side.B, attempt)

            assertEquals(listOf("ensure", "cast q1 B ${attempt.value}"), calls)
        }

    private class RecordingVotes(
        private val calls: MutableList<String>,
    ) : VoteRepository {
        override suspend fun cast(
            questionId: String,
            side: Side,
            attempt: AttemptId,
        ): VoteOutcome {
            calls += "cast $questionId $side ${attempt.value}"
            return VoteOutcome(questionId, side, Tally(votesA = 0, votesB = 1), pointsAwarded = 1, totalPoints = 1)
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
