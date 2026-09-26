package io.ntole.wyr.server.moderation

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.RegisterRequest
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.author.BlockAuthorRequest
import io.ntole.wyr.core.author.UnblockAuthorRequest
import io.ntole.wyr.core.category.CreateCategoryRequest
import io.ntole.wyr.core.category.RenameCategoryRequest
import io.ntole.wyr.core.question.ApproveSubmissionRequest
import io.ntole.wyr.core.question.RejectSubmissionRequest
import io.ntole.wyr.core.question.RestoreQuestionRequest
import io.ntole.wyr.core.question.RetireQuestionRequest
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.question.SubmitQuestionRequest
import io.ntole.wyr.core.report.DismissReportsRequest
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteRequest
import io.ntole.wyr.server.FLOW_ADMIN_TOKEN
import io.ntole.wyr.server.mintGuest
import io.ntole.wyr.server.printed
import io.ntole.wyr.server.runTestServer
import io.ntole.wyr.server.withLogCapture
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The operator's trail (CLAUDE.md §8b, *Logging*): one INFO line for each stored submission and each
 * admin action that changed something, naming ids only, never what a player or the moderator typed,
 * nor a token or a password.
 */
class ActionLogTest {
    @Test
    fun `a stored submission and each admin action log one line of ids and nothing typed`() =
        withLogCapture { logged ->
            runTestServer("action-log") { client, _ ->
                val author = client.registered()
                val first = client.submit(author, "Secret option one")
                val second = client.submit(author, "Secret option two")
                val third = client.submit(author, "Secret option three")

                val actions: List<Pair<String, suspend () -> HttpResponse>> =
                    listOf(
                        "admin approved question ${first.id}" to
                            { client.admin(WyrApi.Paths.ADMIN_APPROVALS, ApproveSubmissionRequest(first.id)) },
                        "admin rejected question ${second.id}" to
                            { client.admin(WyrApi.Paths.ADMIN_REJECTIONS, RejectSubmissionRequest(second.id, REASON)) },
                        "admin retired question ${first.id}" to
                            { client.admin(WyrApi.Paths.ADMIN_RETIREMENTS, RetireQuestionRequest(first.id)) },
                        "admin restored question ${first.id}" to
                            { client.admin(WyrApi.Paths.ADMIN_RESTORATIONS, RestoreQuestionRequest(first.id)) },
                        "admin added category PETS" to
                            {
                                client.admin(
                                    WyrApi.Paths.ADMIN_CATEGORIES,
                                    CreateCategoryRequest(id = "PETS", nameSr = "Љубимци", nameEn = SECRET_NAME),
                                )
                            },
                        "admin renamed category PETS" to
                            {
                                client.admin(
                                    WyrApi.Paths.ADMIN_CATEGORY_RENAMES,
                                    RenameCategoryRequest("PETS", nameSr = "Кућни љубимци", nameEn = SECRET_NAME),
                                )
                            },
                        "admin dismissed 0 reports of question seed-1" to
                            { client.admin(WyrApi.Paths.ADMIN_REPORT_DISMISSALS, DismissReportsRequest("seed-1")) },
                        "admin blocked author, rejecting 1 pending, ${author.playerId}" to
                            {
                                client.admin(
                                    WyrApi.Paths.ADMIN_AUTHOR_BLOCKS,
                                    BlockAuthorRequest(author.playerId, REASON),
                                )
                            },
                        "admin unblocked author ${author.playerId}" to
                            { client.admin(WyrApi.Paths.ADMIN_AUTHOR_UNBLOCKS, UnblockAuthorRequest(author.playerId)) },
                    )
                actions.forEach { (line, act) -> assertEquals(true, act().status.isSuccess(), line) }

                val info = logged.list.filter { it.level.levelStr == "INFO" }.map { it.formattedMessage }
                listOf(first, second, third).forEach { submission ->
                    assertEquals(
                        1,
                        info.count { it == "submission ${submission.id} stored, by player ${author.playerId}" },
                        "one line for ${submission.optionA}",
                    )
                }
                actions.forEach { (line, _) -> assertEquals(1, info.count { it == line }, line) }
                val printed = logged.printed()
                listOf("Secret option", REASON, SECRET_NAME, FLOW_ADMIN_TOKEN, PASSWORD).forEach { secret ->
                    assertFalse(printed.any { secret in it }, "no line holds \"$secret\"")
                }
            }
        }

    private suspend inline fun <reified T : Any> HttpClient.admin(
        path: String,
        body: T,
    ): HttpResponse =
        post(path) {
            header(WyrApi.Headers.ADMIN_TOKEN, FLOW_ADMIN_TOKEN)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /** A guest registered through the API, with three answers' points to spend. */
    private suspend fun HttpClient.registered(): SessionDto {
        val session = mintGuest()
        val name =
            "p" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(12)
        post(WyrApi.Paths.AUTH_REGISTER) {
            bearerAuth(session.accessToken)
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(name, PASSWORD))
        }
        repeat(3) {
            post(WyrApi.Paths.VOTES) {
                bearerAuth(session.accessToken)
                contentType(ContentType.Application.Json)
                setBody(VoteRequest("seed-1", OptionSide.A, attemptId = UUID.randomUUID().toString()))
            }
        }
        return session
    }

    private suspend fun HttpClient.submit(
        session: SessionDto,
        text: String,
    ): SubmissionDto {
        val response =
            post(WyrApi.Paths.QUESTIONS) {
                bearerAuth(session.accessToken)
                contentType(ContentType.Application.Json)
                setBody(SubmitQuestionRequest(text, "Not $text", listOf("FOOD")))
            }
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    private companion object {
        const val REASON = "A reason only the author should read"
        const val SECRET_NAME = "Pets typed by the moderator"
        const val PASSWORD = "a password nobody logs"
    }
}
