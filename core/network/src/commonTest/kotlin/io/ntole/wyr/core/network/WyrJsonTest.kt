package io.ntole.wyr.core.network

import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.core.player.PlayerStatsDto
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionCategoryListSerializer
import io.ntole.wyr.core.question.QuestionDto
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.SubmissionDto
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins the client half of the wire enum rule (CLAUDE.md §5).
 *
 * Nothing else would notice it breaking: the failure only shows up on an installed client older
 * than a server-side enum addition, never in a build where both sides are current.
 */
class WyrJsonTest {
    @Test
    fun `a question filed under a category this build has never heard of still decodes`() {
        val question =
            WyrJson.decodeFromString<QuestionDto>(
                """{"id":"q1","optionA":"fly","optionB":"swim","categories":["FOOD","CATEGORY_FROM_THE_FUTURE"]}""",
            )

        assertEquals(listOf(QuestionCategory.FOOD, QuestionCategory.UNKNOWN), question.categories)
        assertEquals("fly", question.optionA)
    }

    @Test
    fun `a question sent without its categories decodes as filed under none`() {
        val question = WyrJson.decodeFromString<QuestionDto>("""{"id":"q1","optionA":"fly","optionB":"swim"}""")

        assertEquals(emptyList(), question.categories)
    }

    @Test
    fun `a category this build has never heard of in a list decodes as UNKNOWN and keeps the rest`() {
        val categories =
            WyrJson.decodeFromString(
                QuestionCategoryListSerializer,
                """["FOOD","CATEGORY_FROM_THE_FUTURE","ETHICS","ANOTHER_FROM_THE_FUTURE"]""",
            )

        assertEquals(
            listOf(QuestionCategory.FOOD, QuestionCategory.UNKNOWN, QuestionCategory.ETHICS, QuestionCategory.UNKNOWN),
            categories,
        )
    }

    @Test
    fun `coercion alone does not reach the elements of a list so categories need a serializer of their own`() {
        // The premise of QuestionCategoryListSerializer (CLAUDE.md §5): if this ever decodes, the
        // library has started coercing elements and the serializer can go.
        assertFailsWith<SerializationException> {
            WyrJson.decodeFromString<List<QuestionCategory>>("""["FOOD","CATEGORY_FROM_THE_FUTURE"]""")
        }
    }

    @Test
    fun `a list of categories goes on the wire as a plain array of their names`() {
        val categories = listOf(QuestionCategory.FOOD, QuestionCategory.ETHICS)

        val encoded = WyrJson.encodeToString(QuestionCategoryListSerializer, categories)

        assertEquals("""["FOOD","ETHICS"]""", encoded)
        assertEquals(categories, WyrJson.decodeFromString(QuestionCategoryListSerializer, encoded))
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
                """{"id":"q1","optionA":"fly","optionB":"swim","categories":["CATEGORY_FROM_THE_FUTURE","ETHICS"],""" +
                    """"status":"STATUS_FROM_THE_FUTURE","submittedAt":5}""",
            )

        assertEquals(
            SubmissionDto(
                id = "q1",
                optionA = "fly",
                optionB = "swim",
                categories = listOf(QuestionCategory.UNKNOWN, QuestionCategory.ETHICS),
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
