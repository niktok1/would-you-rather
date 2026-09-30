package io.ntole.wyr.core.domain.submission

import io.ntole.wyr.core.domain.session.SessionRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class SubmitQuestionTest {
    private val calls = mutableListOf<String>()

    @Test
    fun `the session is ensured before the question is submitted as given`() =
        runTest {
            val submitQuestion = SubmitQuestion(RecordingSubmissions(calls), RecordingSessions(calls))

            val stored = submitQuestion(" Fly ", "Swim", setOf("SUPERPOWERS", "FOOD"))

            assertEquals(STORED, stored)
            // The options untouched: trimming, like every other content rule, is the server's.
            assertEquals(listOf("ensure", "submit  Fly |Swim|[SUPERPOWERS, FOOD]|null"), calls)
        }

    @Test
    fun `a pick of no category is submitted with the category suggested`() =
        runTest {
            val submitQuestion = SubmitQuestion(RecordingSubmissions(calls), RecordingSessions(calls))

            submitQuestion("Fly", "Swim", emptySet(), categorySuggestion = "Музика")

            // Nothing fits: the moderator files it (CLAUDE.md §8d, *Categories*, *Nothing fits*).
            assertEquals(listOf("ensure", "submit Fly|Swim|[]|Музика"), calls)
        }

    private class RecordingSubmissions(
        private val calls: MutableList<String>,
    ) : SubmissionRepository {
        override suspend fun submit(
            optionA: String,
            optionB: String,
            categories: Set<String>,
            categorySuggestion: String?,
        ): Submission {
            calls += "submit $optionA|$optionB|$categories|$categorySuggestion"
            return STORED
        }

        override suspend fun mine(): List<Submission> = error("a submission lists nothing")
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
        val STORED =
            Submission(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = setOf("FOOD", "SUPERPOWERS"),
                status = SubmissionStatus.PENDING,
                rejectionReason = null,
                submittedAt = Instant.fromEpochMilliseconds(1_790_000_000_000L),
            )
    }
}
