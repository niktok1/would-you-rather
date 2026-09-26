package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.author.AuthorBlockDto
import io.ntole.wyr.core.author.BlockAuthorRequest
import io.ntole.wyr.core.domain.moderation.AuthorBlock
import io.ntole.wyr.core.domain.moderation.ModeratedQuestion
import io.ntole.wyr.core.domain.moderation.ModerationRepository
import io.ntole.wyr.core.domain.moderation.QuestionCursor
import io.ntole.wyr.core.domain.moderation.QuestionFilter
import io.ntole.wyr.core.domain.moderation.RejectionReason
import io.ntole.wyr.core.domain.moderation.ReportedQuestion
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.core.question.AdminQuestionDto
import io.ntole.wyr.core.question.AdminQuestionPageDto
import io.ntole.wyr.core.question.ApproveSubmissionRequest
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.RejectSubmissionRequest
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.report.AdminReportDto
import io.ntole.wyr.core.report.ReportReasonCountDto
import io.ntole.wyr.core.vote.VoteTallyDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.time.Instant
import io.ntole.wyr.core.domain.moderation.ReportReason as DomainReportReason
import io.ntole.wyr.core.report.ReportReason as WireReportReason

class ModerationMapperTest {
    @Test
    fun `an approval names its categories by their ids in id order`() {
        assertEquals(
            ApproveSubmissionRequest("q1", listOf("ABSURD", "ANIMALS", "FOOD")),
            approveSubmissionRequest("q1", setOf("FOOD", "ANIMALS", "ABSURD")),
        )
    }

    @Test
    fun `an approval under no categories names none and so keeps the author's`() {
        assertEquals(ApproveSubmissionRequest("q1", emptyList()), approveSubmissionRequest("q1", emptySet()))
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
                dislikeCount = 3,
                authorId = "p1",
            )

        assertEquals(
            ModeratedQuestion(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = setOf("ETHICS", "FROM_THE_FUTURE"),
                status = SubmissionStatus.RETIRED,
                isSeed = true,
                submittedAt = Instant.fromEpochMilliseconds(1_000L),
                reviewedAt = Instant.fromEpochMilliseconds(2_000L),
                retiredAt = Instant.fromEpochMilliseconds(3_000L),
                rejectionReason = "passed on as the server sent it",
                tally = Tally(votesA = 4, votesB = 1),
                likeCount = 2,
                dislikeCount = 3,
                authorId = "p1",
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
        assertEquals(emptySet(), listed.categories, "sent under none, which no server does, and filed under none")
        assertEquals(null to null, listed.reviewedAt to listed.retiredAt)
    }

    @Test
    fun `a submission in the moderator's queue keeps its author's id`() {
        val queued = SubmissionDto("q1", "Fly", "Swim", listOf("FOOD"), submittedAt = 1L, authorId = "p1")

        assertEquals(queued.toDomain().copy(authorId = "p1"), queued.toModeratorsSubmission())
    }

    @Test
    fun `a reported question lists its question and its reasons most given first`() {
        val question = AdminQuestionDto("q1", "Fly", "Swim", submittedAt = 1L, tally = VoteTallyDto(0, 0))
        val reported =
            AdminReportDto(
                question = question,
                reportCount = 4,
                reasons =
                    listOf(
                        ReportReasonCountDto(WireReportReason.REAL_PERSON, 2),
                        ReportReasonCountDto(WireReportReason.NOT_A_CHOICE, 1),
                        ReportReasonCountDto(WireReportReason.OTHER, 1),
                    ),
                lastReportedAt = 5_000L,
            )

        assertEquals(
            ReportedQuestion(
                question = question.toDomain(),
                reportCount = 4,
                reasons =
                    mapOf(
                        DomainReportReason.REAL_PERSON to 2,
                        DomainReportReason.NOT_A_CHOICE to 1,
                        DomainReportReason.OTHER to 1,
                    ),
                lastReportedAt = Instant.fromEpochMilliseconds(5_000L),
            ),
            reported.toDomain(),
        )
        assertEquals(
            listOf(DomainReportReason.REAL_PERSON, DomainReportReason.NOT_A_CHOICE, DomainReportReason.OTHER),
            reported
                .toDomain()
                .reasons.keys
                .toList(),
            "in the order given",
        )
    }

    @Test
    fun `reasons this build cannot name are added together and then take their place by count`() {
        val reported =
            AdminReportDto(
                question = AdminQuestionDto("q1", "Fly", "Swim", submittedAt = 1L, tally = VoteTallyDto(0, 0)),
                reportCount = 10,
                // The two UNKNOWNs stand for two reasons added server-side since this build.
                reasons =
                    listOf(
                        ReportReasonCountDto(WireReportReason.OFFENSIVE, 3),
                        ReportReasonCountDto(WireReportReason.SPAM, 2),
                        ReportReasonCountDto(WireReportReason.UNKNOWN, 2),
                        ReportReasonCountDto(WireReportReason.UNKNOWN, 2),
                        ReportReasonCountDto(WireReportReason.OTHER, 1),
                    ),
                lastReportedAt = 5_000L,
            ).toDomain()

        assertEquals(4, reported.reasons[DomainReportReason.UNKNOWN])
        assertEquals(
            listOf(
                DomainReportReason.UNKNOWN,
                DomainReportReason.OFFENSIVE,
                DomainReportReason.SPAM,
                DomainReportReason.OTHER,
            ),
            reported.reasons.keys.toList(),
            "most given first, the reasons added together ahead of the rest",
        )
    }

    @Test
    fun `every wire reason maps to its domain namesake`() {
        assertEquals(
            WireReportReason.entries.map { it.name },
            WireReportReason.entries.map { it.toDomain().name },
        )
    }

    @Test
    fun `a block sends its reason as checked and its answer says where the author stands`() {
        val reason = assertNotNull(RejectionReason.of("  Увредљиво "))

        assertEquals(BlockAuthorRequest("p1", "Увредљиво"), blockAuthorRequest("p1", reason))
        assertEquals(
            AuthorBlock("p1", isBlocked = true, rejectedSubmissions = 3),
            AuthorBlockDto("p1", blocked = true, rejectedSubmissions = 3).toDomain(),
        )
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
    fun `a filter names its statuses in declaration order and its categories in id order`() {
        val filter =
            QuestionFilter(
                statuses = setOf(SubmissionStatus.RETIRED, SubmissionStatus.PENDING),
                categories = setOf("FOOD", "ABSURD"),
            )

        assertEquals(listOf(QuestionStatus.PENDING, QuestionStatus.RETIRED), filter.wireStatuses())
        assertEquals(listOf("ABSURD", "FOOD"), filter.wireCategories())
        assertEquals(
            SubmissionStatus.entries.filter { it != SubmissionStatus.OTHER }.map { it.name },
            QuestionFilter(statuses = SubmissionStatus.entries.toSet() - SubmissionStatus.OTHER)
                .wireStatuses()
                .map { it.name },
            "every status this build can name is its wire namesake",
        )
    }

    @Test
    fun `a filter by a status this build cannot name is refused`() {
        assertFailsWith<IllegalArgumentException> {
            QuestionFilter(
                statuses = setOf(SubmissionStatus.OTHER),
            ).wireStatuses()
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
