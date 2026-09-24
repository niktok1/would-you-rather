package io.ntole.wyr.dev.moderation

import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.dev.LogEntry
import io.ntole.wyr.dev.LogResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Instant

class ModerationSectionTest {
    @Test
    fun `a pending submission shows every field the server sent`() {
        assertEquals(
            listOf(
                "q1 PENDING submittedAt=2026-09-24T12:00:00Z",
                "  categories: FOOD, OTHER",
                "  A: Fly",
                "  B: Swim",
            ),
            pendingLines(PENDING),
        )
    }

    @Test
    fun `a reason the server sent shows too`() {
        assertEquals("  reason: a duplicate", pendingLines(PENDING.copy(rejectionReason = "a duplicate")).last())
    }

    @Test
    fun `nothing picked approves under the author's categories`() {
        assertEquals("the author's categories", approvalOf(emptySet()))
        assertEquals("FOOD, RANDOM", approvalOf(setOf(Category.FOOD, Category.RANDOM)))
    }

    @Test
    fun `the token's status never shows the token`() {
        assertEquals("none typed", tokenStatusOf(" "))
        assertEquals("cannot be a token: visible ASCII only, no spaces", tokenStatusOf("s3cret token"))
        assertEquals("ready", tokenStatusOf("s3cret-token"))
        assertFalse("s3cret" in tokenStatusOf("s3cret token"))
    }

    @Test
    fun `an UNKNOWN from an admin route reads as moderation off`() {
        val off = LogEntry("loadPending", "", 0, LogResult.Err(DomainError.UNKNOWN, "404 Not Found"))

        assertEquals(
            "UNKNOWN with a 404 in the HTTP trace: moderation is off on this server (no ADMIN_TOKEN)",
            moderationOffHint(listOf(off)),
        )
    }

    @Test
    fun `any other newest entry gives no hint`() {
        val forbidden = LogEntry("loadPending", "", 0, LogResult.Err(DomainError.FORBIDDEN, "wrong token"))
        val off = LogEntry("loadPending", "", 0, LogResult.Err(DomainError.UNKNOWN, "404 Not Found"))
        val ok = LogEntry("loadPending", "", 0, LogResult.Ok("pending=0"))

        assertNull(moderationOffHint(emptyList()))
        assertNull(moderationOffHint(listOf(forbidden)))
        // Only the newest entry says how the server answers now.
        assertNull(moderationOffHint(listOf(ok, off)))
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
