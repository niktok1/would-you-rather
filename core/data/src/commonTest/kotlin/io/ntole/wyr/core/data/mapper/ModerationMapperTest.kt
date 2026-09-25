package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.domain.moderation.ModeratedQuestion
import io.ntole.wyr.core.domain.moderation.ModerationRepository
import io.ntole.wyr.core.domain.moderation.QuestionCursor
import io.ntole.wyr.core.domain.moderation.QuestionFilter
import io.ntole.wyr.core.domain.moderation.RejectionReason
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.core.question.AdminQuestionDto
import io.ntole.wyr.core.question.AdminQuestionPageDto
import io.ntole.wyr.core.question.ApproveSubmissionRequest
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.RejectSubmissionRequest
import io.ntole.wyr.core.vote.VoteTallyDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.time.Instant

class ModerationMapperTest {
    @Test
    fun `an approval names its categories by their ids in declaration order`() {
        assertEquals(
            ApproveSubmissionRequest("q1", listOf("FOOD", "ABSURD")),
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
    fun `every field of a question in the moderator's list lands in its own field`() {
        val listed =
            AdminQuestionDto(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = listOf("ETHICS", "FROM_THE_FUTURE"),
                status = QuestionStatus.RETIRED,
                seed = true,
                submittedAt = 1_000L,
                reviewedAt = 2_000L,
                retiredAt = 3_000L,
                rejectionReason = "passed on as the server sent it",
                tally = VoteTallyDto(votesA = 4, votesB = 1),
                likeCount = 2,
            )

        assertEquals(
            ModeratedQuestion(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = setOf(Category.ETHICS, Category.OTHER),
                status = SubmissionStatus.RETIRED,
                isSeed = true,
                submittedAt = Instant.fromEpochMilliseconds(1_000L),
                reviewedAt = Instant.fromEpochMilliseconds(2_000L),
                retiredAt = Instant.fromEpochMilliseconds(3_000L),
                rejectionReason = "passed on as the server sent it",
                tally = Tally(votesA = 4, votesB = 1),
                likeCount = 2,
            ),
            listed.toDomain(),
        )
    }

    @Test
    fun `a question with a status this build cannot name lists as OTHER and one never reviewed has no times`() {
        val listed =
            AdminQuestionDto(
                id = "seed-1",
                optionA = "Fly",
                optionB = "Swim",
                status = QuestionStatus.UNKNOWN,
                submittedAt = 1_000L,
                tally = VoteTallyDto(votesA = 0, votesB = 0),
            ).toDomain()

        assertEquals(SubmissionStatus.OTHER, listed.status)
        assertEquals(setOf(Category.OTHER), listed.categories, "filed under nothing this build can name")
        assertEquals(null to null, listed.reviewedAt to listed.retiredAt)
    }

    @Test
    fun `a page's cursor goes back as it came and the last page has none`() {
        val question = AdminQuestionDto("q1", "Fly", "Swim", submittedAt = 1L, tally = VoteTallyDto(0, 0))

        assertEquals(
            QuestionCursor("1:q1"),
            AdminQuestionPageDto(listOf(question), nextCursor = "1:q1").toDomain().next,
        )
        assertEquals(null, AdminQuestionPageDto(listOf(question)).toDomain().next)
    }

    @Test
    fun `a filter names its statuses and categories by their wire names in declaration order`() {
        val filter =
            QuestionFilter(
                statuses = setOf(SubmissionStatus.RETIRED, SubmissionStatus.PENDING),
                categories = setOf(Category.RANDOM, Category.FOOD),
            )

        assertEquals(listOf(QuestionStatus.PENDING, QuestionStatus.RETIRED), filter.wireStatuses())
        assertEquals(listOf("FOOD", "ABSURD"), filter.wireCategories())
        assertEquals(
            SubmissionStatus.entries.filter { it != SubmissionStatus.OTHER }.map { it.name },
            QuestionFilter(statuses = SubmissionStatus.entries.toSet() - SubmissionStatus.OTHER)
                .wireStatuses()
                .map { it.name },
            "every status this build can name is its wire namesake",
        )
    }

    @Test
    fun `a filter by what this build cannot name is refused`() {
        assertFailsWith<IllegalArgumentException> {
            QuestionFilter(
                statuses = setOf(SubmissionStatus.OTHER),
            ).wireStatuses()
        }
        assertFailsWith<IllegalArgumentException> {
            QuestionFilter(
                categories = setOf(Category.OTHER),
            ).wireCategories()
        }
    }

    @Test
    fun `the client's page size is the most the server lists at once`() {
        // As for the reason's limit below: asked for more, the server would list no more than this.
        assertEquals(WyrApi.Limits.MAX_PAGE_SIZE, ModerationRepository.PAGE_SIZE)
    }

    @Test
    fun `the client's limit on a reason is the server's`() {
        // The domain cannot see :core, so it keeps its own copy of the number, checked here.
        assertEquals(WyrApi.Limits.MAX_REJECTION_REASON_LENGTH, RejectionReason.MAX_LENGTH)
    }
}
