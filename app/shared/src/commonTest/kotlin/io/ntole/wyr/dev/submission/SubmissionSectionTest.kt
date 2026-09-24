package io.ntole.wyr.dev.submission

import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class SubmissionSectionTest {
    @Test
    fun `a rejected submission shows the moderator's reason`() {
        val rejected = PENDING.copy(status = SubmissionStatus.REJECTED, rejectionReason = "a duplicate")

        assertEquals(
            listOf(
                "q1 REJECTED submittedAt=2026-09-24T12:00:00Z",
                "  categories: FOOD, OTHER",
                "  A: Fly",
                "  B: Swim",
                "  reason: a duplicate",
            ),
            submissionLines(rejected),
        )
    }

    @Test
    fun `a rejection the server gave no reason for says so`() {
        val rejected = PENDING.copy(status = SubmissionStatus.REJECTED)

        assertEquals("  reason: none given", submissionLines(rejected).last())
    }

    @Test
    fun `a pending submission shows no reason`() {
        assertEquals(
            listOf(
                "q1 PENDING submittedAt=2026-09-24T12:00:00Z",
                "  categories: FOOD, OTHER",
                "  A: Fly",
                "  B: Swim",
            ),
            submissionLines(PENDING),
        )
    }

    @Test
    fun `a status this build cannot name shows as OTHER`() {
        assertEquals(
            "q1 OTHER submittedAt=2026-09-24T12:00:00Z",
            submissionLines(PENDING.copy(status = SubmissionStatus.OTHER)).first(),
        )
    }

    @Test
    fun `no category picked says so and picks are named`() {
        assertEquals("none picked", pickedOf(emptySet()))
        assertEquals("FOOD, ETHICS", pickedOf(setOf(Category.FOOD, Category.ETHICS)))
    }

    private companion object {
        val PENDING =
            Submission(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = setOf(Category.FOOD, Category.OTHER),
                status = SubmissionStatus.PENDING,
                rejectionReason = null,
                submittedAt = Instant.parse("2026-09-24T12:00:00Z"),
            )
    }
}
