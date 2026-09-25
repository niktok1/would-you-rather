package io.ntole.wyr.core.network

import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.core.player.PlayerStatsDto
import io.ntole.wyr.core.question.QuestionDto
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.reaction.Reaction
import kotlinx.serialization.Serializable
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
    fun `a question filed under a category this build has never heard of still decodes`() {
        // Categories are server data, not an enum (CLAUDE.md §5): a new one is only an id.
        val question =
            WyrJson.decodeFromString<QuestionDto>(
                """{"id":"q1","optionA":"fly","optionB":"swim","categories":["FOOD","CATEGORY_FROM_THE_FUTURE"]}""",
            )

        assertEquals(listOf("FOOD", "CATEGORY_FROM_THE_FUTURE"), question.categories)
        assertEquals("fly", question.optionA)
    }

    @Test
    fun `a question sent without its categories decodes as filed under none`() {
        val question = WyrJson.decodeFromString<QuestionDto>("""{"id":"q1","optionA":"fly","optionB":"swim"}""")

        assertEquals(emptyList(), question.categories)
    }

    @Test
    fun `a question's categories go on the wire as a plain array of their ids`() {
        // Exactly what the enum they replace sent, so a build from before reads the first ones as ever.
        val question = QuestionDto(id = "q1", optionA = "fly", optionB = "swim", categories = listOf("FOOD", "ETHICS"))

        val encoded = WyrJson.encodeToString(question)

        assertEquals(true, """"categories":["FOOD","ETHICS"]""" in encoded, encoded)
        assertEquals(question, WyrJson.decodeFromString<QuestionDto>(encoded))
    }

    @Test
    fun `an error code this build has never heard of decodes as UNKNOWN`() {
        val error = WyrJson.decodeFromString<ErrorDto>("""{"code":"CODE_FROM_THE_FUTURE","message":"m"}""")

        assertEquals(ErrorCode.UNKNOWN, error.code)
        assertEquals("m", error.message)
    }

    @Test
    fun `a submission status this build has never heard of decodes as UNKNOWN beside a category it has not`() {
        val submission =
            WyrJson.decodeFromString<SubmissionDto>(
                """{"id":"q1","optionA":"fly","optionB":"swim","categories":["CATEGORY_FROM_THE_FUTURE","ETHICS"],""" +
                    """"status":"STATUS_FROM_THE_FUTURE","submittedAt":5}""",
            )

        assertEquals(
            SubmissionDto(
                id = "q1",
                optionA = "fly",
                optionB = "swim",
                categories = listOf("CATEGORY_FROM_THE_FUTURE", "ETHICS"),
                status = QuestionStatus.UNKNOWN,
                rejectionReason = null,
                submittedAt = 5,
            ),
            submission,
        )
    }

    @Test
    fun `a retired submission and the conflict a retirement can end in decode as themselves`() {
        val retired =
            WyrJson.decodeFromString<SubmissionDto>(
                """{"id":"q1","optionA":"fly","optionB":"swim","categories":["ETHICS"],""" +
                    """"status":"RETIRED","submittedAt":5}""",
            )
        val conflict = WyrJson.decodeFromString<ErrorDto>("""{"code":"WRONG_STATUS","message":"m"}""")

        assertEquals(QuestionStatus.RETIRED, retired.status)
        assertEquals(ErrorCode.WRONG_STATUS, conflict.code)
    }

    /**
     * What a build from before retirement makes of a retired submission and of the conflict, read
     * through this build's `WyrJson` into the contract as that build had it: the three statuses and
     * the error codes it knew, each with its `UNKNOWN` default. It lists the submission as a status it
     * cannot name, rather than failing the whole list, which is what the `UNKNOWN` members are for.
     */
    @Test
    fun `a build from before retirement reads a retired submission and its conflict as UNKNOWN`() {
        val retired =
            WyrJson.decodeFromString<SubmissionBeforeRetirement>(
                """{"id":"q1","status":"RETIRED","submittedAt":5}""",
            )
        val conflict = WyrJson.decodeFromString<ErrorBeforeRetirement>("""{"code":"WRONG_STATUS","message":"m"}""")

        assertEquals(StatusBeforeRetirement.UNKNOWN, retired.status)
        assertEquals(CodeBeforeRetirement.UNKNOWN, conflict.code)
    }

    @Serializable
    private enum class StatusBeforeRetirement { PENDING, APPROVED, REJECTED, UNKNOWN }

    @Serializable
    private enum class CodeBeforeRetirement { QUESTION_NOT_FOUND, ALREADY_DECIDED, FORBIDDEN, UNKNOWN }

    @Serializable
    private data class SubmissionBeforeRetirement(
        val id: String,
        val status: StatusBeforeRetirement = StatusBeforeRetirement.UNKNOWN,
        val submittedAt: Long,
    )

    @Serializable
    private data class ErrorBeforeRetirement(
        val message: String? = null,
        val code: CodeBeforeRetirement = CodeBeforeRetirement.UNKNOWN,
    )

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
                likesReceived = 0,
            ),
            stats,
        )
    }

    @Test
    fun `a question sent without its reactions decodes as one nobody has reacted to`() {
        val question = WyrJson.decodeFromString<QuestionDto>("""{"id":"q1","optionA":"fly","optionB":"swim"}""")

        assertEquals(
            Triple(0, 0, Reaction.NONE),
            Triple(question.likeCount, question.dislikeCount, question.myReaction),
        )
    }

    @Test
    fun `a submission sent without its counts decodes with none`() {
        val submission =
            WyrJson.decodeFromString<SubmissionDto>(
                """{"id":"q1","optionA":"fly","optionB":"swim","status":"APPROVED","submittedAt":1}""",
            )

        assertEquals(Triple(0, 0, 0), Triple(submission.likeCount, submission.dislikeCount, submission.answerCount))
    }

    @Test
    fun `a field this build has never heard of is ignored`() {
        val error = WyrJson.decodeFromString<ErrorDto>("""{"code":"ALREADY_VOTED","retryAfterSeconds":30}""")

        assertEquals(ErrorCode.ALREADY_VOTED, error.code)
    }
}
