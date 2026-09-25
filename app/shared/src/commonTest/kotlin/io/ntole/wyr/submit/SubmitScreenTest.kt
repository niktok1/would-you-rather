package io.ntole.wyr.submit

import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.submission.OptionProblem
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `categories read in Serbian in the order the question lists them`() {
        val known =
            listOf(
                Category(id = "FOOD", nameSr = "Храна", nameEn = "Food"),
                Category(id = "ETHICS", nameSr = "Етика", nameEn = "Ethics"),
            )

        assertEquals("Етика, Храна", categoryNames(linkedSetOf("ETHICS", "FOOD"), known))
        // One not read yet, by its id.
        assertEquals("Храна, ANIMALS", categoryNames(linkedSetOf("FOOD", "ANIMALS"), known))
    }

    @Test
    fun `categories that cannot be read say so in the player's words`() {
        assertEquals(
            "Can't reach the game to list the categories. Check your connection.",
            categoriesFailureMessage(SubmitFailure(DomainError.NETWORK)),
        )
        assertEquals(
            "Couldn't list the categories. Try again.",
            categoriesFailureMessage(SubmitFailure(DomainError.SERVER)),
        )
    }

    @Test
    fun `the screen says submitting costs a point that a rejection pays back`() {
        assertEquals(
            "Submitting a question costs 1 point, paid back if it is rejected. " +
                "Once approved, each like it gets earns you 1.",
            POINTS_NOTE,
        )
    }

    @Test
    fun `too few points reads as what submitting costs and how to earn it`() {
        // Its own line: never the catch-all, which would ask the player to try the same again.
        assertEquals(
            "You need 1 point to submit. Answer a question to earn it.",
            failureMessage(SubmitFailure(DomainError.NOT_ENOUGH_POINTS)),
        )
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
                categories = setOf("SUPERPOWERS"),
                status = SubmissionStatus.PENDING,
                rejectionReason = null,
                submittedAt = Instant.parse("2026-09-25T12:00:00Z"),
            )
    }
}
