package io.ntole.wyr.core.domain.submission

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class SubmissionTest {
    @Test
    fun `a submission filed under no category is refused`() {
        // Filed as a question is, under at least one (CLAUDE.md §8d); one this build cannot name is OTHER.
        assertFailsWith<IllegalArgumentException> {
            Submission(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = emptySet(),
                status = SubmissionStatus.PENDING,
                rejectionReason = null,
                submittedAt = Instant.fromEpochMilliseconds(0),
            )
        }
    }
}
