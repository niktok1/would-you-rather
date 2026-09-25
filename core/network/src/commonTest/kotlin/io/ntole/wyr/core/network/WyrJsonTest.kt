package io.ntole.wyr.core.network

import io.ntole.wyr.core.auth.GuestSessionDto
import io.ntole.wyr.core.auth.RecoverRequest
import io.ntole.wyr.core.auth.RecoverySecretDto
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.core.player.PlayerStatsDto
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionCategoryListSerializer
import io.ntole.wyr.core.question.QuestionDto
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.SubmissionDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `a question sent without its likes decodes as liked by nobody`() {
        val question = WyrJson.decodeFromString<QuestionDto>("""{"id":"q1","optionA":"fly","optionB":"swim"}""")

        assertEquals(0, question.likeCount)
        assertEquals(false, question.likedByMe)
    }

    @Test
    fun `a field this build has never heard of is ignored`() {
        val error = WyrJson.decodeFromString<ErrorDto>("""{"code":"ALREADY_VOTED","retryAfterSeconds":30}""")

        assertEquals(ErrorCode.ALREADY_VOTED, error.code)
    }

    @Test
    fun `a guest session decodes with its recovery secret and without one from a server that has no recovery`() {
        val guest = WyrJson.decodeFromString<GuestSessionDto>(GUEST_SESSION)
        val fromBeforeRecovery = WyrJson.decodeFromString<GuestSessionDto>(SESSION)

        assertEquals("secret", guest.recoverySecret)
        assertEquals(SessionDto("p1", "access", "refresh", 900), guest.session())
        assertNull(fromBeforeRecovery.recoverySecret)
        assertEquals(guest.session(), fromBeforeRecovery.session())
    }

    /**
     * What a build from before recovery makes of a guest session: the session it always read, the
     * secret ignored. That build's [SessionDto] is this one's, which recovery left as it was.
     */
    @Test
    fun `a build from before recovery reads a guest session as the session it knew`() {
        assertEquals(SessionDto("p1", "access", "refresh", 900), WyrJson.decodeFromString<SessionDto>(GUEST_SESSION))
    }

    /** What makes the test above hold for every field a session will ever have. */
    @Test
    fun `a guest session holds every field a session does under the same name and the secret`() {
        fun SerialDescriptor.names(): List<String> = (0 until elementsCount).map(::getElementName)

        assertEquals(
            SessionDto.serializer().descriptor.names() + "recoverySecret",
            GuestSessionDto.serializer().descriptor.names(),
        )
    }

    /**
     * The secret never rotates, so a copy in a log or a failed test's message recovers its player
     * until the player replaces it (CLAUDE.md §8a, *Recovery*). Each of these would print it as a data
     * class does.
     */
    @Test
    fun `no DTO that carries the recovery secret shows it`() {
        val shown =
            listOf(
                WyrJson.decodeFromString<GuestSessionDto>(GUEST_SESSION),
                RecoverRequest(recoverySecret = "secret"),
                RecoverySecretDto(recoverySecret = "secret"),
            ).map { it.toString() }

        // Each secret here is the word itself, which shows after an = only as a field's value.
        shown.forEach { assertFalse("=secret" in it, it) }
        assertEquals(
            "GuestSessionDto(playerId=p1, accessToken=access, refreshToken=refresh, " +
                "accessTokenExpiresInSeconds=900, recoverySecret=<redacted>)",
            shown.first(),
        )
        assertTrue("recoverySecret=null" in WyrJson.decodeFromString<GuestSessionDto>(SESSION).toString())
    }

    @Test
    fun `a refused recovery decodes as itself and as UNKNOWN in a build from before recovery`() {
        val refused = """{"code":"INVALID_RECOVERY_SECRET","message":"m"}"""

        assertEquals(ErrorCode.INVALID_RECOVERY_SECRET, WyrJson.decodeFromString<ErrorDto>(refused).code)
        assertEquals(CodeBeforeRecovery.UNKNOWN, WyrJson.decodeFromString<ErrorBeforeRecovery>(refused).code)
    }

    /** Some of the error codes a build from before recovery knew, with its `UNKNOWN` default. */
    @Serializable
    private enum class CodeBeforeRecovery { UNAUTHORIZED, INVALID_REFRESH_TOKEN, RATE_LIMITED, UNKNOWN }

    @Serializable
    private data class ErrorBeforeRecovery(
        val message: String? = null,
        val code: CodeBeforeRecovery = CodeBeforeRecovery.UNKNOWN,
    )

    private companion object {
        const val SESSION =
            """{"playerId":"p1","accessToken":"access","refreshToken":"refresh",""" +
                """"accessTokenExpiresInSeconds":900}"""
        const val GUEST_SESSION =
            """{"playerId":"p1","accessToken":"access","refreshToken":"refresh",""" +
                """"accessTokenExpiresInSeconds":900,"recoverySecret":"secret"}"""
    }
}
