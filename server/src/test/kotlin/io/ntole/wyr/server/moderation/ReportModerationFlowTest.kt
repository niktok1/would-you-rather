package io.ntole.wyr.server.moderation

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.RegisterRequest
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.author.AuthorBlockDto
import io.ntole.wyr.core.author.BlockAuthorRequest
import io.ntole.wyr.core.author.UnblockAuthorRequest
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.core.player.PlayerStatsDto
import io.ntole.wyr.core.question.AdminQuestionPageDto
import io.ntole.wyr.core.question.ApproveSubmissionRequest
import io.ntole.wyr.core.question.QuestionPageDto
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.question.SubmissionListDto
import io.ntole.wyr.core.question.SubmitQuestionRequest
import io.ntole.wyr.core.report.AdminReportListDto
import io.ntole.wyr.core.report.DismissReportsRequest
import io.ntole.wyr.core.report.ReportReason
import io.ntole.wyr.core.report.ReportReasonCountDto
import io.ntole.wyr.core.report.ReportRequest
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteRequest
import io.ntole.wyr.server.FLOW_ADMIN_TOKEN
import io.ntole.wyr.server.mintGuest
import io.ntole.wyr.server.runTestServer
import io.ntole.wyr.server.vote.Scoring
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The moderator's side of reports and authors over HTTP (CLAUDE.md §8d, *Reports* and *Moderation*):
 * the reported questions, their dismissal, authors named by id, and blocking one.
 */
class ReportModerationFlowTest {
    @Test
    fun `the moderator lists reported questions most reported first with their reasons counted`() =
        runTestServer("admin-reports") { client, _ ->
            val reporters = List(3) { client.mintGuest() }
            client.report(reporters[0], "seed-1", ReportReason.SPAM)
            client.report(reporters[1], "seed-1", ReportReason.SPAM)
            client.report(reporters[2], "seed-1", ReportReason.OFFENSIVE)
            client.report(reporters[0], "seed-2", ReportReason.OTHER)
            awaitNextMillisecond()
            client.report(reporters[1], "seed-3", ReportReason.NOT_A_CHOICE)

            val listed = client.reports().reports

            assertEquals(listOf("seed-1", "seed-3", "seed-2"), listed.map { it.question.id }, "a tie: the later first")
            val top = listed.first()
            assertEquals(3, top.reportCount)
            assertEquals(
                listOf(ReportReasonCountDto(ReportReason.SPAM, 2), ReportReasonCountDto(ReportReason.OFFENSIVE, 1)),
                top.reasons,
            )
            assertTrue(top.question.seed, "the question as the list of every question shows it")
            assertEquals(listOf("seed-1"), client.reports("?${WyrApi.Query.LIMIT}=1").reports.map { it.question.id })
        }

    @Test
    fun `a dismissal clears a question's reports and leaves it hidden from its reporters`() =
        runTestServer("admin-report-dismissals") { client, _ ->
            val reporter = client.mintGuest()
            client.report(reporter, "seed-1", ReportReason.SPAM)

            repeat(2) { attempt ->
                val dismissed = client.admin(WyrApi.Paths.ADMIN_REPORT_DISMISSALS, DismissReportsRequest("seed-1"))
                assertEquals(HttpStatusCode.NoContent, dismissed.status, "attempt ${attempt + 1}")
            }

            assertEquals(emptyList(), client.reports().reports)
            assertFalse("seed-1" in client.feedIds(reporter), "still hidden from the reporter")
            assertRefused(
                client.admin(WyrApi.Paths.ADMIN_REPORT_DISMISSALS, DismissReportsRequest("no-such-question")),
                HttpStatusCode.NotFound,
                ErrorCode.QUESTION_NOT_FOUND,
            )
        }

    @Test
    fun `the admin routes name each author by id and nothing a player is sent does`() =
        runTestServer("admin-author-ids") { client, _ ->
            val author = client.registeredWithPoints()
            val submitted = client.submit(author, "Stored")

            val queued =
                client
                    .get(WyrApi.Paths.ADMIN_SUBMISSIONS) { header(WyrApi.Headers.ADMIN_TOKEN, FLOW_ADMIN_TOKEN) }
                    .body<SubmissionListDto>()
                    .submissions
            assertEquals(author.playerId, queued.single().authorId, "the queue")
            val approved = client.admin(WyrApi.Paths.ADMIN_APPROVALS, ApproveSubmissionRequest(submitted.id))
            assertEquals(author.playerId, approved.body<SubmissionDto>().authorId, "a decision's answer")
            val listed =
                client
                    .get("${WyrApi.Paths.ADMIN_QUESTIONS}?${WyrApi.Query.LIMIT}=${WyrApi.Limits.MAX_PAGE_SIZE}") {
                        header(WyrApi.Headers.ADMIN_TOKEN, FLOW_ADMIN_TOKEN)
                    }.body<AdminQuestionPageDto>()
                    .questions
                    .associate { it.id to it.authorId }
            assertEquals(author.playerId, listed.getValue(submitted.id))
            assertEquals(null, listed.getValue("seed-1"), "a seed has no author")

            // The author's own list and a submission's answer never name them, not even to themselves.
            assertEquals(null, submitted.authorId)
            val own =
                client
                    .get(WyrApi.Paths.MY_QUESTIONS) { bearerAuth(author.accessToken) }
                    .body<JsonObject>()
                    .getValue("submissions")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(JsonNull, own["authorId"])
        }

    @Test
    fun `blocking an author rejects what is pending and refuses their next submission until unblocked`() =
        runTestServer("admin-author-blocks") { client, _ ->
            val author = client.registeredWithPoints(points = 3)
            val pending = List(2) { index -> client.submit(author, "Pending $index") }
            val approved = client.submit(author, "Approved")
            client.admin(WyrApi.Paths.ADMIN_APPROVALS, ApproveSubmissionRequest(approved.id))

            val blocked = client.admin(WyrApi.Paths.ADMIN_AUTHOR_BLOCKS, BlockAuthorRequest(author.playerId, " Spam "))

            assertEquals(AuthorBlockDto(author.playerId, blocked = true, rejectedSubmissions = 2), blocked.body())
            val own = client.mySubmissions(author).associateBy { it.id }
            pending.forEach { submission ->
                assertEquals(
                    QuestionStatus.REJECTED to "Spam",
                    own.getValue(submission.id).let {
                        it.status to
                            it.rejectionReason
                    },
                )
            }
            assertEquals(QuestionStatus.APPROVED, own.getValue(approved.id).status)
            assertEquals(2 * Scoring.SUBMISSION_COST, client.stats(author).totalPoints, "both paid back")

            // Refused before the options are checked: nothing they could type would pass.
            assertRefused(
                client.submitRaw(author, SubmitQuestionRequest("", "", listOf("FOOD"))),
                HttpStatusCode.Forbidden,
                ErrorCode.SUBMISSIONS_BLOCKED,
            )

            val unblocked = client.admin(WyrApi.Paths.ADMIN_AUTHOR_UNBLOCKS, UnblockAuthorRequest(author.playerId))
            assertEquals(AuthorBlockDto(author.playerId, blocked = false), unblocked.body())
            assertEquals(HttpStatusCode.Created, client.submitRaw(author, request("Again")).status)
        }

    @Test
    fun `a block or an unblock names a real author and a block a reason the rules take`() =
        runTestServer("admin-author-refusals") { client, _ ->
            val author = client.mintGuest()
            assertRefused(
                client.admin(WyrApi.Paths.ADMIN_AUTHOR_BLOCKS, BlockAuthorRequest("no-such-player", "Spam")),
                HttpStatusCode.NotFound,
                ErrorCode.AUTHOR_NOT_FOUND,
            )
            assertRefused(
                client.admin(WyrApi.Paths.ADMIN_AUTHOR_UNBLOCKS, UnblockAuthorRequest("no-such-player")),
                HttpStatusCode.NotFound,
                ErrorCode.AUTHOR_NOT_FOUND,
            )
            listOf(" ", "two\nlines", "x".repeat(WyrApi.Limits.MAX_REJECTION_REASON_LENGTH + 1)).forEach { reason ->
                assertRefused(
                    client.admin(WyrApi.Paths.ADMIN_AUTHOR_BLOCKS, BlockAuthorRequest(author.playerId, reason)),
                    HttpStatusCode.BadRequest,
                    ErrorCode.VALIDATION_FAILED,
                )
            }
        }

    private suspend fun assertRefused(
        response: HttpResponse,
        status: HttpStatusCode,
        code: ErrorCode,
    ) {
        assertEquals(status, response.status)
        assertEquals(code, response.body<ErrorDto>().code)
    }

    private suspend fun HttpClient.report(
        session: SessionDto,
        questionId: String,
        reason: ReportReason,
    ) {
        val response =
            post(WyrApi.Paths.REPORTS) {
                bearerAuth(session.accessToken)
                contentType(ContentType.Application.Json)
                setBody(ReportRequest(questionId, reason))
            }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    private suspend fun HttpClient.reports(query: String = ""): AdminReportListDto =
        get(WyrApi.Paths.ADMIN_REPORTS + query) { header(WyrApi.Headers.ADMIN_TOKEN, FLOW_ADMIN_TOKEN) }.body()

    private suspend inline fun <reified T : Any> HttpClient.admin(
        path: String,
        body: T,
    ): HttpResponse =
        post(path) {
            header(WyrApi.Headers.ADMIN_TOKEN, FLOW_ADMIN_TOKEN)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /** A guest registered through the API, then given [points] by answering as many times. */
    private suspend fun HttpClient.registeredWithPoints(points: Int = 1): SessionDto {
        val session = mintGuest()
        val name =
            "p" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(12)
        val registered =
            post(WyrApi.Paths.AUTH_REGISTER) {
                bearerAuth(session.accessToken)
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest(name, "a password"))
            }
        assertEquals(HttpStatusCode.OK, registered.status)
        repeat(points) {
            val vote =
                post(WyrApi.Paths.VOTES) {
                    bearerAuth(session.accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(VoteRequest("seed-1", OptionSide.A, attemptId = UUID.randomUUID().toString()))
                }
            assertEquals(HttpStatusCode.OK, vote.status)
        }
        return session
    }

    private fun request(text: String) = SubmitQuestionRequest(text, "Not $text", listOf("FOOD"))

    private suspend fun HttpClient.submit(
        session: SessionDto,
        text: String,
    ): SubmissionDto {
        val response = submitRaw(session, request(text))
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    private suspend fun HttpClient.submitRaw(
        session: SessionDto,
        request: SubmitQuestionRequest,
    ): HttpResponse =
        post(WyrApi.Paths.QUESTIONS) {
            bearerAuth(session.accessToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private suspend fun HttpClient.mySubmissions(session: SessionDto): List<SubmissionDto> =
        get(WyrApi.Paths.MY_QUESTIONS) { bearerAuth(session.accessToken) }.body<SubmissionListDto>().submissions

    private suspend fun HttpClient.stats(session: SessionDto): PlayerStatsDto =
        get(WyrApi.Paths.ME) { bearerAuth(session.accessToken) }.body()

    private suspend fun HttpClient.feedIds(session: SessionDto): List<String> =
        get("${WyrApi.Paths.QUESTIONS}?${WyrApi.Query.LIMIT}=${WyrApi.Limits.MAX_PAGE_SIZE}") {
            bearerAuth(session.accessToken)
        }.body<QuestionPageDto>().questions.map { it.id }

    /** Returns once the clock has moved on, so the next report is stamped later than the last. */
    private fun awaitNextMillisecond() {
        val now = System.currentTimeMillis()
        while (System.currentTimeMillis() == now) Thread.onSpinWait()
    }
}
