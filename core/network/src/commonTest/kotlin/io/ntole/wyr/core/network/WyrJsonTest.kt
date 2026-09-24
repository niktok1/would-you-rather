package io.ntole.wyr.core.network

import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.core.player.PlayerStatsDto
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionDto
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.SubmissionDto
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the client half of the wire enum rule (CLAUDE.md §5).
 *
 * Nothing else would notice it breaking: the failure only shows up on an installed client older
 * than a server-side enum addition, never in a build where both sides are current.
 */
class WyrJsonTest {
    @Test
    fun `a category this build has never heard of decodes as UNKNOWN`() {
        val question =
            WyrJson.decodeFromString<QuestionDto>(
                """{"id":"q1","optionA":"fly","optionB":"swim","category":"CATEGORY_FROM_THE_FUTURE"}""",
            )

        assertEquals(QuestionCategory.UNKNOWN, question.category)
        assertEquals("fly", question.optionA)
    }

    @Test
    fun `an error code this build has never heard of decodes as UNKNOWN`() {
        val error = WyrJson.decodeFromString<ErrorDto>("""{"code":"CODE_FROM_THE_FUTURE","message":"m"}""")

        assertEquals(ErrorCode.UNKNOWN, error.code)
        assertEquals("m", error.message)
    }

    @Test
    fun `a submission status or category this build has never heard of decodes as UNKNOWN`() {
        val submission =
            WyrJson.decodeFromString<SubmissionDto>(
                """{"id":"q1","optionA":"fly","optionB":"swim","category":"CATEGORY_FROM_THE_FUTURE",""" +
                    """"status":"STATUS_FROM_THE_FUTURE","submittedAt":5}""",
            )

        assertEquals(
            SubmissionDto(
                id = "q1",
                optionA = "fly",
                optionB = "swim",
                category = QuestionCategory.UNKNOWN,
                status = QuestionStatus.UNKNOWN,
                rejectionReason = null,
                submittedAt = 5,
            ),
            submission,
        )
    }

    @Test
    fun `a stat the server does not send decodes as its default`() {
        val stats = WyrJson.decodeFromString<PlayerStatsDto>("""{"playerId":"p1","totalPoints":3}""")

        assertEquals(
            PlayerStatsDto(
                playerId = "p1",
                totalPoints = 3,
                answersGiven = 0,
                questionsAnswered = 0,
                cycle = 1,
                dueThisCycle = 0,
            ),
            stats,
        )
    }

    @Test
    fun `a field this build has never heard of is ignored`() {
        val error = WyrJson.decodeFromString<ErrorDto>("""{"code":"ALREADY_VOTED","retryAfterSeconds":30}""")

        assertEquals(ErrorCode.ALREADY_VOTED, error.code)
    }
}
