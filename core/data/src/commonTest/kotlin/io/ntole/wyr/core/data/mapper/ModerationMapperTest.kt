package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.domain.moderation.RejectionReason
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.question.ApproveSubmissionRequest
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.RejectSubmissionRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ModerationMapperTest {
    @Test
    fun `an approval names its categories by their wire names in declaration order`() {
        assertEquals(
            ApproveSubmissionRequest("q1", listOf(QuestionCategory.FOOD, QuestionCategory.RANDOM)),
            approveSubmissionRequest("q1", setOf(Category.RANDOM, Category.FOOD)),
        )
    }

    @Test
    fun `an approval under no categories names none and so keeps the author's`() {
        assertEquals(ApproveSubmissionRequest("q1", emptyList()), approveSubmissionRequest("q1", emptySet()))
    }

    @Test
    fun `an approval under OTHER is refused`() {
        listOf(setOf(Category.OTHER), setOf(Category.FOOD, Category.OTHER)).forEach { categories ->
            assertFailsWith<IllegalArgumentException>("$categories") { approveSubmissionRequest("q1", categories) }
        }
    }

    @Test
    fun `a rejection sends its reason as checked`() {
        val reason = assertNotNull(RejectionReason.of("  a duplicate "))

        assertEquals(RejectSubmissionRequest("q1", "a duplicate"), rejectSubmissionRequest("q1", reason))
    }

    @Test
    fun `the client's limit on a reason is the server's`() {
        // The domain cannot see :core, so it keeps its own copy of the number, checked here.
        assertEquals(WyrApi.Limits.MAX_REJECTION_REASON_LENGTH, RejectionReason.MAX_LENGTH)
    }
}
