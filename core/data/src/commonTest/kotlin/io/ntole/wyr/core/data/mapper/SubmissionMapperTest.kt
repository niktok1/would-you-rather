package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.core.network.WyrJson
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.question.SubmitQuestionRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class SubmissionMapperTest {
    @Test
    fun `every field of a submission lands in its own field`() {
        // All different, so two fields swapped in the mapping cannot go unnoticed.
        val dto =
            SubmissionDto(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = listOf("ETHICS"),
                status = QuestionStatus.REJECTED,
                rejectionReason = "a duplicate",
                submittedAt = 1_790_000_000_123L,
                likeCount = 5,
                dislikeCount = 2,
                answerCount = 34,
                votesA = 21,
                votesB = 13,
            )

        assertEquals(
            Submission(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = setOf("ETHICS"),
                status = SubmissionStatus.REJECTED,
                rejectionReason = "a duplicate",
                submittedAt = Instant.fromEpochMilliseconds(1_790_000_000_123L),
                likeCount = 5,
                dislikeCount = 2,
                answerCount = 34,
                tally = Tally(votesA = 21, votesB = 13),
            ),
            dto.toDomain(),
        )
    }

    @Test
    fun `a negative count of either side maps to none`() {
        val sent = SubmissionDto("q1", "Fly", "Swim", listOf("FOOD"), submittedAt = 1L, votesA = -1, votesB = 3)

        assertEquals(Tally(votesA = 0, votesB = 3), sent.toDomain().tally)
    }

    @Test
    fun `a player's own submission never carries its author's id`() {
        // Only the admin routes send one, but a player's list maps none whatever it is sent.
        val sent = SubmissionDto("q1", "Fly", "Swim", listOf("FOOD"), submittedAt = 1L, authorId = "p1")

        assertEquals(null, sent.toDomain().authorId)
    }

    @Test
    fun `every wire status maps to its domain namesake and UNKNOWN to OTHER`() {
        QuestionStatus.entries.forEach { wire ->
            val expected =
                if (wire == QuestionStatus.UNKNOWN) SubmissionStatus.OTHER else SubmissionStatus.valueOf(wire.name)

            assertEquals(expected, wire.toDomain(), "$wire")
        }
    }

    @Test
    fun `a submission with a status and a category this build has never heard of still lists`() {
        // What a server newer than this build sends: the list must load, and claim nothing it cannot tell.
        val dto =
            WyrJson.decodeFromString<SubmissionDto>(
                """{"id":"q1","optionA":"Fly","optionB":"Swim","categories":["CATEGORY_FROM_THE_FUTURE","ETHICS"],""" +
                    """"status":"STATUS_FROM_THE_FUTURE","submittedAt":5}""",
            )

        val submission = dto.toDomain()

        assertEquals(SubmissionStatus.OTHER, submission.status)
        // As a list, so the order is checked too: a question's rule, every id kept as the server sent it.
        assertEquals(listOf("CATEGORY_FROM_THE_FUTURE", "ETHICS"), submission.categories.toList())
    }

    @Test
    fun `a submission's categories map as a question's do`() {
        val cases =
            listOf(
                listOf("ABSURD", "FOOD") to listOf("ABSURD", "FOOD"),
                listOf("FROM_THE_FUTURE", "SUPERPOWERS", "FROM_THE_FUTURE") to listOf("FROM_THE_FUTURE", "SUPERPOWERS"),
                // Which no server sends: a submission is filed under at least one.
                emptyList<String>() to emptyList(),
            )

        cases.forEach { (categories, expected) ->
            val dto = SUBMITTED.copy(categories = categories)

            assertEquals(expected, dto.toDomain().categories.toList(), "$categories")
        }
    }

    @Test
    fun `a submission goes on the wire with its options as given and its categories in id order`() {
        val request = submitQuestionRequest(" Fly ", "Swim", setOf("SUPERPOWERS", "FOOD", "ETHICS"))

        // Trimming, like every rule about the options, is the server's.
        assertEquals(
            SubmitQuestionRequest(
                optionA = " Fly ",
                optionB = "Swim",
                categories = listOf("ETHICS", "FOOD", "SUPERPOWERS"),
            ),
            request,
        )
    }

    @Test
    fun `a category added after this build goes on the wire as its id`() {
        // Categories are server data: any id the server listed can be submitted under, as it is.
        val request = submitQuestionRequest("Fly", "Swim", setOf("ANIMALS"))

        assertEquals(listOf("ANIMALS"), request.categories)
    }

    @Test
    fun `a submission under no category is refused`() {
        // The server would refuse it as malformed (VALIDATION_FAILED), which no correct client sends.
        assertFailsWith<IllegalArgumentException> { submitQuestionRequest("Fly", "Swim", emptySet()) }
    }

    private companion object {
        val SUBMITTED =
            SubmissionDto(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = listOf("FOOD"),
                status = QuestionStatus.PENDING,
                submittedAt = 0,
            )
    }
}
