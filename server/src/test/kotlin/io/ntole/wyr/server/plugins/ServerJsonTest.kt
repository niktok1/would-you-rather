package io.ntole.wyr.server.plugins

import io.ntole.wyr.core.question.ApproveSubmissionRequest
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
 * Categories on the wire as `ServerJson` writes and reads them: plain ids, no enum (CLAUDE.md §5), so
 * the first ones go out exactly as the enum they replace sent them, and a request's ids reach the
 * store as sent, which refuses one no category has (`CategoryStore.checked`).
 */
class ServerJsonTest {
    @Test
    fun `a question goes out with its categories as a plain array of their ids`() {
        val question =
            QuestionDto(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = listOf("FOOD", "ETHICS"),
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
    fun `a submission's categories are read as the ids it names whatever they are`() {
        // Kept as sent, so the store refuses the request instead of filing the question under FOOD alone.
        val request =
            ServerJson.decodeFromString<SubmitQuestionRequest>(
                """{"optionA":"Fly","optionB":"Swim","categories":["FOOD","FROM_THE_FUTURE"]}""",
            )

        assertEquals(listOf("FOOD", "FROM_THE_FUTURE"), request.categories)
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
        assertEquals(listOf("ETHICS", "FROM_THE_FUTURE"), replacing.categories)
    }

    private fun fieldOf(
        json: String,
        name: String,
    ): JsonElement? = ServerJson.parseToJsonElement(json).jsonObject[name]
}
