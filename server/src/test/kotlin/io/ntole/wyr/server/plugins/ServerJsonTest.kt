package io.ntole.wyr.server.plugins

import io.ntole.wyr.core.question.ApproveSubmissionRequest
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionDto
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.question.SubmitQuestionRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The server half of the wire enum rule for a list of categories (CLAUDE.md §5), as `ServerJson`
 * writes and reads it. `WyrJsonTest` in `:core:network` pins the client half.
 */
class ServerJsonTest {
    @Test
    fun `a question goes out with its categories as a plain array of their names`() {
        val question =
            QuestionDto(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = listOf(QuestionCategory.FOOD, QuestionCategory.ETHICS),
            )

        assertEquals("""["FOOD","ETHICS"]""", fieldOf(ServerJson.encodeToString(question), "categories").toString())
    }

    @Test
    fun `categories at their default are still sent`() {
        // encodeDefaults: a field is never left out for equalling its default, a list included.
        val submission =
            SubmissionDto(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = emptyList(),
                status = QuestionStatus.PENDING,
                submittedAt = 1,
            )

        assertEquals(JsonArray(emptyList()), fieldOf(ServerJson.encodeToString(submission), "categories"))
    }

    @Test
    fun `a submission naming a category this build does not know reads it as UNKNOWN`() {
        // Kept rather than dropped, so checkedSubmission refuses the request instead of filing the
        // question under FOOD alone.
        val request =
            ServerJson.decodeFromString<SubmitQuestionRequest>(
                """{"optionA":"Fly","optionB":"Swim","categories":["FOOD","FROM_THE_FUTURE"]}""",
            )

        assertEquals(listOf(QuestionCategory.FOOD, QuestionCategory.UNKNOWN), request.categories)
    }

    @Test
    fun `an approval reads its categories as a submission does and none when it leaves them out`() {
        // None keeps the author's categories, so a client that leaves the field out asks for exactly that.
        val keeping = ServerJson.decodeFromString<ApproveSubmissionRequest>("""{"questionId":"q1"}""")
        val replacing =
            ServerJson.decodeFromString<ApproveSubmissionRequest>(
                """{"questionId":"q1","categories":["ETHICS","FROM_THE_FUTURE"]}""",
            )

        assertEquals(ApproveSubmissionRequest("q1", categories = emptyList()), keeping)
        assertEquals(listOf(QuestionCategory.ETHICS, QuestionCategory.UNKNOWN), replacing.categories)
    }

    private fun fieldOf(
        json: String,
        name: String,
    ): JsonElement? = ServerJson.parseToJsonElement(json).jsonObject[name]
}
