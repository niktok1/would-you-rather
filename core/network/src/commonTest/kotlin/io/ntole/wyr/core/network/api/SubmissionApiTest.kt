package io.ntole.wyr.core.network.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.network.ApiException
import io.ntole.wyr.core.network.BASE_URL
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.WyrJson
import io.ntole.wyr.core.network.jsonHeaders
import io.ntole.wyr.core.network.respondErrorDto
import io.ntole.wyr.core.network.session
import io.ntole.wyr.core.network.storeHolding
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.question.SubmissionListDto
import io.ntole.wyr.core.question.SubmitQuestionRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** What a submission sends, what the author's list asks for, and what a refusal throws. */
class SubmissionApiTest {
    @Test
    fun `a submission is posted with the bearer and read back as the server stored it`() =
        runTest {
            val engine = MockEngine { respond(WyrJson.encodeToString(STORED), HttpStatusCode.Created, jsonHeaders) }
            val request =
                SubmitQuestionRequest(
                    optionA = " fly ",
                    optionB = "swim",
                    categories = listOf("SUPERPOWERS", "FOOD"),
                )

            val stored = submissionApi(engine).submit(request)

            assertEquals(STORED, stored)
            val sent = engine.requestHistory.single()
            assertEquals(HttpMethod.Post, sent.method)
            assertEquals(WyrApi.Paths.QUESTIONS, sent.url.encodedPath)
            assertEquals("Bearer access-a", sent.headers[HttpHeaders.Authorization])
            // As given: trimming and ordering are the server's.
            val body = sent.body.toByteArray().decodeToString()
            assertEquals(
                """{"optionA":" fly ","optionB":"swim","categories":["SUPERPOWERS","FOOD"]}""",
                body,
            )
        }

    @Test
    fun `the author's submissions are read with the bearer`() =
        runTest {
            val list = SubmissionListDto(listOf(STORED, STORED.copy(id = "q0", status = QuestionStatus.APPROVED)))
            val engine = MockEngine { respond(WyrJson.encodeToString(list), HttpStatusCode.OK, jsonHeaders) }

            val mine = submissionApi(engine).mine()

            assertEquals(list, mine)
            val sent = engine.requestHistory.single()
            assertEquals(HttpMethod.Get, sent.method)
            assertEquals(WyrApi.Paths.MY_QUESTIONS, sent.url.encodedPath)
            assertEquals("Bearer access-a", sent.headers[HttpHeaders.Authorization])
        }

    @Test
    fun `a refused submission throws the server's code and message`() =
        runTest {
            val engine =
                MockEngine { respondErrorDto(HttpStatusCode.UnprocessableEntity, ErrorCode.INVALID_SUBMISSION) }

            val failure = assertFailsWith<ApiException> { submissionApi(engine).submit(STORED_REQUEST) }

            assertEquals(ErrorCode.INVALID_SUBMISSION, failure.code)
            assertEquals(422, failure.status)
            // The server's diagnostic text, carried as the message and never shown to the player.
            assertEquals("test", failure.message)
        }

    private fun submissionApi(engine: MockEngine): SubmissionApi =
        SubmissionApi(WyrHttpClient.create(BASE_URL, storeHolding(session("a")), engine))

    private companion object {
        val STORED_REQUEST =
            SubmitQuestionRequest(optionA = "fly", optionB = "swim", categories = listOf("FOOD"))

        val STORED =
            SubmissionDto(
                id = "q1",
                optionA = "fly",
                optionB = "swim",
                categories = listOf("FOOD", "SUPERPOWERS"),
                status = QuestionStatus.PENDING,
                submittedAt = 1_790_000_000_000L,
            )
    }
}
