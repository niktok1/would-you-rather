package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionStatus
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
            )

        assertEquals(
            Submission(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = setOf(Category.ETHICS),
                status = SubmissionStatus.REJECTED,
                rejectionReason = "a duplicate",
                submittedAt = Instant.fromEpochMilliseconds(1_790_000_000_123L),
            ),
            dto.toDomain(),
        )
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
        // As a list, so the order is checked too: a question's rule, OTHER beside the rest.
        assertEquals(listOf(Category.ETHICS, Category.OTHER), submission.categories.toList())
    }

    @Test
    fun `a submission's categories map as a question's do`() {
        val cases =
            listOf(
                listOf("ABSURD", "FOOD") to listOf(Category.FOOD, Category.RANDOM),
                listOf("FROM_THE_FUTURE", "SUPERPOWERS", "FROM_THE_FUTURE") to
                    listOf(Category.SUPERPOWERS, Category.OTHER),
                // Never empty: a submission is filed under at least one.
                emptyList<String>() to listOf(Category.OTHER),
            )

        cases.forEach { (categories, expected) ->
            val dto = SUBMITTED.copy(categories = categories)

            assertEquals(expected, dto.toDomain().categories.toList(), "$categories")
        }
    }

    @Test
    fun `a submission goes on the wire with its options as given and its categories in declaration order`() {
        val request = submitQuestionRequest(" Fly ", "Swim", setOf(Category.RANDOM, Category.FOOD, Category.ETHICS))

        // Trimming, like every rule about the options, is the server's.
        assertEquals(
            SubmitQuestionRequest(
                optionA = " Fly ",
                optionB = "Swim",
                categories = listOf("FOOD", "ETHICS", "ABSURD"),
            ),
            request,
        )
    }

    @Test
    fun `every category a question can be submitted under goes on the wire as itself`() {
        Category.selectable.forEach { category ->
            val request = submitQuestionRequest("Fly", "Swim", setOf(category))

            assertEquals(listOf(category.toWireOrNull()), request.categories, "$category")
        }
    }

    @Test
    fun `a submission under no category is refused`() {
        // The server would refuse it as malformed (VALIDATION_FAILED), which no correct client sends.
        assertFailsWith<IllegalArgumentException> { submitQuestionRequest("Fly", "Swim", emptySet()) }
    }

    @Test
    fun `a submission under OTHER is refused whatever else it names`() {
        // Not dropped: sent as the rest alone it would be filed under less than the author picked.
        listOf(setOf(Category.OTHER), setOf(Category.FOOD, Category.OTHER)).forEach { categories ->
            assertFailsWith<IllegalArgumentException>("$categories") {
                submitQuestionRequest("Fly", "Swim", categories)
            }
        }
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
