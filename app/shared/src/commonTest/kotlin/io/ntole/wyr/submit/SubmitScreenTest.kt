package io.ntole.wyr.submit

import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.OptionProblem
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** What the Submit screen says, in the player's words. */
class SubmitScreenTest {
    @Test
    fun `each status reads as where the question stands`() {
        assertEquals("Pending: waiting for a moderator", statusLine(PENDING))
        assertEquals("Approved: in the game", statusLine(PENDING.copy(status = SubmissionStatus.APPROVED)))
        assertEquals("Retired: out of the game for now", statusLine(PENDING.copy(status = SubmissionStatus.RETIRED)))
        assertEquals(
            "Its status is one this version of the app can't show",
            statusLine(PENDING.copy(status = SubmissionStatus.OTHER)),
        )
    }

    @Test
    fun `a rejection reads with the moderator's reason`() {
        val rejected = PENDING.copy(status = SubmissionStatus.REJECTED, rejectionReason = "Too close to a seed")

        assertEquals("Rejected: Too close to a seed", statusLine(rejected))
        assertEquals("Rejected", statusLine(rejected.copy(rejectionReason = null)))
    }

    @Test
    fun `each option problem reads as what to put right`() {
        assertEquals("One line, up to 200 characters.", optionHint(null, same = false))
        assertEquals("Write something here.", optionHint(OptionProblem.BLANK, same = false))
        assertEquals("At most 200 characters.", optionHint(OptionProblem.TOO_LONG, same = false))
        assertEquals("One line, with no line breaks or tabs.", optionHint(OptionProblem.NOT_ONE_LINE, same = false))
        assertEquals("The two options must be different.", optionHint(null, same = true))
    }

    @Test
    fun `categories read in the player's words in declaration order`() {
        assertEquals("Food, Ethics", categoryNames(setOf(Category.ETHICS, Category.FOOD)))
        assertEquals("Superpowers, Other", categoryNames(setOf(Category.OTHER, Category.SUPERPOWERS)))
    }

    @Test
    fun `the screen says submitting earns no points`() {
        assertTrue(POINTS_NOTE.startsWith("Submitting earns no points"), POINTS_NOTE)
    }

    @Test
    fun `a failure nobody can act on asks to try again`() {
        assertEquals(
            "Something went wrong. Try again.",
            failureMessage(SubmitFailure(DomainError.SERVER)),
        )
        assertEquals(
            "Too many tries. Wait a moment, then try again.",
            failureMessage(SubmitFailure(DomainError.RATE_LIMITED)),
        )
    }

    private companion object {
        val PENDING =
            Submission(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = setOf(Category.SUPERPOWERS),
                status = SubmissionStatus.PENDING,
                rejectionReason = null,
                submittedAt = Instant.parse("2026-09-25T12:00:00Z"),
            )
    }
}
