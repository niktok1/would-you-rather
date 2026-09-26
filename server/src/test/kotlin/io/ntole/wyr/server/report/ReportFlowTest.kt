package io.ntole.wyr.server.report

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.core.question.QuestionPageDto
import io.ntole.wyr.server.mintGuest
import io.ntole.wyr.server.runTestServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Reporting and hiding questions over HTTP (CLAUDE.md §8d, *Reports*). */
class ReportFlowTest {
    @Test
    fun `a report is 204 and the reporter is never served the question again`() =
        runTestServer("report-flow") { client, _ ->
            val (reporter, other) = client.mintGuest() to client.mintGuest()

            val reported = client.send(reporter, WyrApi.Paths.REPORTS, """{"questionId":"seed-1","reason":"SPAM"}""")

            assertEquals(HttpStatusCode.NoContent, reported.status)
            assertFalse("seed-1" in client.feedIds(reporter))
            assertTrue("seed-1" in client.feedIds(other))
        }

    @Test
    fun `hiding a question or its author is 204 and hides it`() =
        runTestServer("hide-flow") { client, _ ->
            val player = client.mintGuest()

            val question = client.send(player, WyrApi.Paths.HIDDEN_QUESTIONS, """{"questionId":"seed-1"}""")
            val author = client.send(player, WyrApi.Paths.HIDDEN_AUTHORS, """{"questionId":"seed-2"}""")

            assertEquals(HttpStatusCode.NoContent, question.status)
            assertEquals(HttpStatusCode.NoContent, author.status)
            val served = client.feedIds(player)
            assertFalse("seed-1" in served)
            assertFalse("seed-2" in served, "a seed's author hides the seed")
        }

    @Test
    fun `a report that gives no real reason is 400`() =
        runTestServer("report-reasons") { client, _ ->
            val reporter = client.mintGuest()

            listOf(
                """{"questionId":"seed-1"}""",
                """{"questionId":"seed-1","reason":"UNKNOWN"}""",
                """{"questionId":"seed-1","reason":"A_REASON_FROM_A_NEWER_BUILD"}""",
            ).forEach { body ->
                assertRefused(
                    client.send(reporter, WyrApi.Paths.REPORTS, body),
                    HttpStatusCode.BadRequest,
                    ErrorCode.VALIDATION_FAILED,
                    body,
                )
            }
            assertTrue("seed-1" in client.feedIds(reporter), "nothing was hidden")
        }

    @Test
    fun `each needs a session and a question the player is served`() =
        runTestServer("report-refusals") { client, _ ->
            val player = client.mintGuest()
            val routes =
                mapOf(
                    WyrApi.Paths.REPORTS to """"reason":"OTHER",""",
                    WyrApi.Paths.HIDDEN_QUESTIONS to "",
                    WyrApi.Paths.HIDDEN_AUTHORS to "",
                )

            routes.forEach { (path, rest) ->
                fun body(questionId: String) = """{$rest"questionId":"$questionId"}"""

                assertRefused(
                    client.post(path) {
                        contentType(ContentType.Application.Json)
                        setBody(body("seed-1"))
                    },
                    HttpStatusCode.Unauthorized,
                    ErrorCode.UNAUTHORIZED,
                    "$path with no session",
                )
                assertRefused(
                    client.send(player, path, body("no-such-question")),
                    HttpStatusCode.NotFound,
                    ErrorCode.QUESTION_NOT_FOUND,
                    "$path of a question nobody is served",
                )
                assertRefused(
                    client.send(player, path, body(" ")),
                    HttpStatusCode.BadRequest,
                    ErrorCode.VALIDATION_FAILED,
                    "$path of a blank id",
                )
            }
        }

    private suspend fun assertRefused(
        response: HttpResponse,
        status: HttpStatusCode,
        code: ErrorCode,
        case: String,
    ) {
        assertEquals(status, response.status, case)
        assertEquals(code, response.body<ErrorDto>().code, case)
    }

    private suspend fun HttpClient.send(
        session: SessionDto,
        path: String,
        body: String,
    ): HttpResponse =
        post(path) {
            bearerAuth(session.accessToken)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.feedIds(session: SessionDto): List<String> =
        get("${WyrApi.Paths.QUESTIONS}?${WyrApi.Query.LIMIT}=${WyrApi.Limits.MAX_PAGE_SIZE}") {
            bearerAuth(session.accessToken)
        }.body<QuestionPageDto>().questions.map { it.id }
}
