package io.ntole.wyr.core.domain.submission

import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.session.SessionRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class GetMySubmissionsTest {
    private val calls = mutableListOf<String>()

    @Test
    fun `the session is ensured before the submissions are listed`() =
        runTest {
            val getMySubmissions = GetMySubmissions(RecordingSubmissions(calls), RecordingSessions(calls))

            assertEquals(MINE, getMySubmissions())
            assertEquals(listOf("ensure", "mine"), calls)
        }

    private class RecordingSubmissions(
        private val calls: MutableList<String>,
    ) : SubmissionRepository {
        override suspend fun submit(
            optionA: String,
            optionB: String,
            categories: Set<Category>,
        ): Submission = error("a list submits nothing")

        override suspend fun mine(): List<Submission> {
            calls += "mine"
            return MINE
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
    }

    private companion object {
        val MINE =
            listOf(
                Submission(
                    id = "q2",
                    optionA = "Fly",
                    optionB = "Swim",
                    categories = setOf(Category.SUPERPOWERS),
                    status = SubmissionStatus.REJECTED,
                    rejectionReason = "a duplicate",
                    submittedAt = Instant.fromEpochMilliseconds(1_790_000_000_001L),
                ),
                Submission(
                    id = "q1",
                    optionA = "Tea",
                    optionB = "Coffee",
                    categories = setOf(Category.FOOD),
                    status = SubmissionStatus.PENDING,
                    rejectionReason = null,
                    submittedAt = Instant.fromEpochMilliseconds(1_790_000_000_000L),
                ),
            )
    }
}
