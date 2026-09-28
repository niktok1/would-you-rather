package io.ntole.wyr.core.network.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.author.AuthorBlockDto
import io.ntole.wyr.core.author.BlockAuthorRequest
import io.ntole.wyr.core.author.UnblockAuthorRequest
import io.ntole.wyr.core.category.CategoryDto
import io.ntole.wyr.core.category.CreateCategoryRequest
import io.ntole.wyr.core.category.RenameCategoryRequest
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.network.ApiException
import io.ntole.wyr.core.network.BASE_URL
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.WyrJson
import io.ntole.wyr.core.network.jsonHeaders
import io.ntole.wyr.core.network.respondEmptyPage
import io.ntole.wyr.core.network.respondErrorDto
import io.ntole.wyr.core.network.respondSession
import io.ntole.wyr.core.network.session
import io.ntole.wyr.core.network.storeHolding
import io.ntole.wyr.core.player.DeleteAccountRequest
import io.ntole.wyr.core.question.AdminQuestionDto
import io.ntole.wyr.core.question.AdminQuestionPageDto
import io.ntole.wyr.core.question.ApproveSubmissionRequest
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.RejectSubmissionRequest
import io.ntole.wyr.core.question.RestoreQuestionRequest
import io.ntole.wyr.core.question.RetireQuestionRequest
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.question.SubmissionListDto
import io.ntole.wyr.core.report.AdminReportDto
import io.ntole.wyr.core.report.AdminReportListDto
import io.ntole.wyr.core.report.DismissReportsRequest
import io.ntole.wyr.core.report.ReportReason
import io.ntole.wyr.core.report.ReportReasonCountDto
import io.ntole.wyr.core.vote.VoteTallyDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** What each admin request carries, what it leaves to the player's session, and what it keeps out of a failure. */
class ModerationApiTest {
    @Test
    fun `the pending queue is asked for with the admin token and the player's bearer left as it was`() =
        runTest {
            val engine = MockEngine { respondOk(QUEUE) }

            val queue = moderationApi(engine, storeHolding(session("a"))).pending(ADMIN_TOKEN)

            assertEquals(QUEUE, queue)
            val sent = engine.requestHistory.single()
            assertEquals(HttpMethod.Get, sent.method)
            assertEquals(WyrApi.Paths.ADMIN_SUBMISSIONS, sent.url.encodedPath)
            assertEquals(QuestionStatus.PENDING.name, sent.url.parameters[WyrApi.Query.STATUS])
            assertEquals("${WyrApi.Limits.DEFAULT_PAGE_SIZE}", sent.url.parameters[WyrApi.Query.LIMIT])
            assertEquals(ADMIN_TOKEN, sent.headers[WyrApi.Headers.ADMIN_TOKEN])
            // The Auth plugin's, as on every request. The admin routes ignore it.
            assertEquals("Bearer access-a", sent.headers[HttpHeaders.Authorization])
        }

    @Test
    fun `an admin request needs no player session and makes none`() =
        runTest {
            val store = storeHolding(null)
            val engine = MockEngine { respondOk(QUEUE) }

            moderationApi(engine, store).pending(ADMIN_TOKEN)

            val sent = engine.requestHistory.single()
            assertEquals(ADMIN_TOKEN, sent.headers[WyrApi.Headers.ADMIN_TOKEN])
            assertNull(sent.headers[HttpHeaders.Authorization])
            assertNull(store.read())
        }

    @Test
    fun `an approval is posted with the admin token and the categories to file it under`() =
        runTest {
            val engine = MockEngine { respondOk(APPROVED) }
            val request = ApproveSubmissionRequest("q1", listOf("FOOD", "ABSURD"))

            val approved = moderationApi(engine, storeHolding(session("a"))).approve(ADMIN_TOKEN, request)

            assertEquals(APPROVED, approved)
            val sent = engine.requestHistory.single()
            assertEquals(HttpMethod.Post, sent.method)
            assertEquals(WyrApi.Paths.ADMIN_APPROVALS, sent.url.encodedPath)
            assertEquals(ADMIN_TOKEN, sent.headers[WyrApi.Headers.ADMIN_TOKEN])
            assertEquals(
                """{"questionId":"q1","categories":["FOOD","ABSURD"]}""",
                sent.body.toByteArray().decodeToString(),
            )
        }

    @Test
    fun `an approval that keeps the author's categories names none`() =
        runTest {
            val engine = MockEngine { respondOk(APPROVED) }

            moderationApi(engine, storeHolding(session("a"))).approve(ADMIN_TOKEN, ApproveSubmissionRequest("q1"))

            val sent = engine.requestHistory.single()
            // The server reads a missing list as none, which keeps the author's.
            assertEquals("""{"questionId":"q1"}""", sent.body.toByteArray().decodeToString())
        }

    @Test
    fun `a rejection is posted with the admin token and the reason`() =
        runTest {
            val engine = MockEngine { respondOk(REJECTED) }

            val rejected =
                moderationApi(engine, storeHolding(session("a")))
                    .reject(ADMIN_TOKEN, RejectSubmissionRequest("q1", "a duplicate"))

            assertEquals(REJECTED, rejected)
            val sent = engine.requestHistory.single()
            assertEquals(HttpMethod.Post, sent.method)
            assertEquals(WyrApi.Paths.ADMIN_REJECTIONS, sent.url.encodedPath)
            assertEquals(ADMIN_TOKEN, sent.headers[WyrApi.Headers.ADMIN_TOKEN])
            assertEquals(
                """{"questionId":"q1","reason":"a duplicate"}""",
                sent.body.toByteArray().decodeToString(),
            )
        }

    @Test
    fun `the question list is asked for with every filter value and the cursor and the limit and the admin token`() =
        runTest {
            val engine = MockEngine { respondOk(PAGE) }

            val page =
                moderationApi(engine, storeHolding(session("a"))).questions(
                    ADMIN_TOKEN,
                    statuses = listOf(QuestionStatus.PENDING, QuestionStatus.RETIRED),
                    categories = listOf("FOOD", "ETHICS"),
                    cursor = "1790000000000:q1",
                    limit = 50,
                )

            assertEquals(PAGE, page)
            val sent = engine.requestHistory.single()
            assertEquals(HttpMethod.Get, sent.method)
            assertEquals(WyrApi.Paths.ADMIN_QUESTIONS, sent.url.encodedPath)
            assertEquals(listOf("PENDING", "RETIRED"), sent.url.parameters.getAll(WyrApi.Query.STATUS))
            assertEquals(listOf("FOOD", "ETHICS"), sent.url.parameters.getAll(WyrApi.Query.CATEGORY))
            assertEquals("1790000000000:q1", sent.url.parameters[WyrApi.Query.CURSOR], "sent back as it came")
            assertEquals("50", sent.url.parameters[WyrApi.Query.LIMIT])
            assertEquals(ADMIN_TOKEN, sent.headers[WyrApi.Headers.ADMIN_TOKEN])
        }

    @Test
    fun `the first page of every question names no filter and no cursor`() =
        runTest {
            val engine = MockEngine { respondOk(PAGE) }

            moderationApi(engine, storeHolding(null)).questions(ADMIN_TOKEN)

            val parameters =
                engine.requestHistory
                    .single()
                    .url.parameters
            assertEquals(setOf(WyrApi.Query.LIMIT), parameters.names(), "none is every one")
            assertEquals("${WyrApi.Limits.DEFAULT_PAGE_SIZE}", parameters[WyrApi.Query.LIMIT])
        }

    @Test
    fun `a retirement and a restoration are posted with the admin token and the question's id`() =
        runTest {
            val engine =
                MockEngine { request ->
                    respondOk(if (request.url.encodedPath == WyrApi.Paths.ADMIN_RETIREMENTS) RETIRED else LISTED)
                }
            val api = moderationApi(engine, storeHolding(session("a")))

            assertEquals(RETIRED, api.retire(ADMIN_TOKEN, RetireQuestionRequest("q1")))
            assertEquals(LISTED, api.restore(ADMIN_TOKEN, RestoreQuestionRequest("q1")))

            assertEquals(
                listOf(WyrApi.Paths.ADMIN_RETIREMENTS, WyrApi.Paths.ADMIN_RESTORATIONS),
                engine.requestHistory.map { it.url.encodedPath },
            )
            engine.requestHistory.forEach { sent ->
                assertEquals(HttpMethod.Post, sent.method)
                assertEquals(ADMIN_TOKEN, sent.headers[WyrApi.Headers.ADMIN_TOKEN])
                assertEquals("""{"questionId":"q1"}""", sent.body.toByteArray().decodeToString())
            }
        }

    @Test
    fun `a category is added and renamed with the admin token and its names`() =
        runTest {
            val engine =
                MockEngine { request ->
                    val added = CategoryDto(id = "FAST_FOOD", nameSr = "Брза храна", nameEn = "Fast food")
                    if (request.url.encodedPath == WyrApi.Paths.ADMIN_CATEGORIES) {
                        respond(WyrJson.encodeToString(added), HttpStatusCode.Created, jsonHeaders)
                    } else {
                        respondOk(added.copy(nameSr = "Брза клопа"))
                    }
                }
            val api = moderationApi(engine, storeHolding(session("a")))

            val added = api.addCategory(ADMIN_TOKEN, CreateCategoryRequest(nameSr = "Брза храна", nameEn = "Fast food"))
            val renamed =
                api.renameCategory(ADMIN_TOKEN, RenameCategoryRequest("FAST_FOOD", "Брза клопа", "Fast food"))

            assertEquals("FAST_FOOD", added.id)
            assertEquals("Брза клопа", renamed.nameSr)
            assertEquals(
                listOf(WyrApi.Paths.ADMIN_CATEGORIES, WyrApi.Paths.ADMIN_CATEGORY_RENAMES),
                engine.requestHistory.map { it.url.encodedPath },
            )
            engine.requestHistory.forEach { sent ->
                assertEquals(HttpMethod.Post, sent.method)
                assertEquals(ADMIN_TOKEN, sent.headers[WyrApi.Headers.ADMIN_TOKEN])
            }
            // No id: the server makes it from the English name.
            assertEquals(
                """{"nameSr":"Брза храна","nameEn":"Fast food"}""",
                engine.requestHistory
                    .first()
                    .body
                    .toByteArray()
                    .decodeToString(),
            )
        }

    @Test
    fun `the reported questions are asked for with the admin token and the limit`() =
        runTest {
            val engine = MockEngine { respondOk(REPORTS) }

            val reports = moderationApi(engine, storeHolding(session("a"))).reports(ADMIN_TOKEN, limit = 100)

            assertEquals(REPORTS, reports)
            val sent = engine.requestHistory.single()
            assertEquals(HttpMethod.Get, sent.method)
            assertEquals(WyrApi.Paths.ADMIN_REPORTS, sent.url.encodedPath)
            assertEquals(setOf(WyrApi.Query.LIMIT), sent.url.parameters.names(), "no cursor, no filter")
            assertEquals("100", sent.url.parameters[WyrApi.Query.LIMIT])
            assertEquals(ADMIN_TOKEN, sent.headers[WyrApi.Headers.ADMIN_TOKEN])
        }

    @Test
    fun `a dismissal is posted with the admin token and the question's id and reads nothing back`() =
        runTest {
            // Answered as the server answers it: 204, with no body to decode.
            val engine = MockEngine { respond("", HttpStatusCode.NoContent) }

            moderationApi(engine, storeHolding(session("a"))).dismissReports(ADMIN_TOKEN, DismissReportsRequest("q1"))

            val sent = engine.requestHistory.single()
            assertEquals(HttpMethod.Post, sent.method)
            assertEquals(WyrApi.Paths.ADMIN_REPORT_DISMISSALS, sent.url.encodedPath)
            assertEquals(ADMIN_TOKEN, sent.headers[WyrApi.Headers.ADMIN_TOKEN])
            assertEquals("""{"questionId":"q1"}""", sent.body.toByteArray().decodeToString())
        }

    @Test
    fun `an account's deletion is posted with the admin token and names the account one way alone`() =
        runTest {
            val engine = MockEngine { respond("", HttpStatusCode.NoContent) }
            val api = moderationApi(engine, storeHolding(session("a")))

            api.deleteAccount(ADMIN_TOKEN, DeleteAccountRequest(username = "leaving"))
            api.deleteAccount(ADMIN_TOKEN, DeleteAccountRequest(accountId = "p1"))

            engine.requestHistory.forEach { sent ->
                assertEquals(HttpMethod.Post, sent.method)
                assertEquals(WyrApi.Paths.ADMIN_ACCOUNT_DELETIONS, sent.url.encodedPath)
                assertEquals(ADMIN_TOKEN, sent.headers[WyrApi.Headers.ADMIN_TOKEN])
            }
            assertEquals(
                listOf("""{"username":"leaving"}""", """{"accountId":"p1"}"""),
                engine.requestHistory.map { it.body.toByteArray().decodeToString() },
            )
        }

    @Test
    fun `a block and an unblock are posted with the admin token and the author's id`() =
        runTest {
            val engine =
                MockEngine { request ->
                    val blocked = request.url.encodedPath == WyrApi.Paths.ADMIN_AUTHOR_BLOCKS
                    respondOk(AuthorBlockDto("p1", blocked = blocked, rejectedSubmissions = if (blocked) 2 else 0))
                }
            val api = moderationApi(engine, storeHolding(session("a")))

            val block = api.blockAuthor(ADMIN_TOKEN, BlockAuthorRequest("p1", "Увредљиво"))
            val unblock = api.unblockAuthor(ADMIN_TOKEN, UnblockAuthorRequest("p1"))

            assertEquals(AuthorBlockDto("p1", blocked = true, rejectedSubmissions = 2), block)
            assertEquals(AuthorBlockDto("p1", blocked = false), unblock)
            assertEquals(
                listOf(WyrApi.Paths.ADMIN_AUTHOR_BLOCKS, WyrApi.Paths.ADMIN_AUTHOR_UNBLOCKS),
                engine.requestHistory.map { it.url.encodedPath },
            )
            engine.requestHistory.forEach { sent ->
                assertEquals(HttpMethod.Post, sent.method)
                assertEquals(ADMIN_TOKEN, sent.headers[WyrApi.Headers.ADMIN_TOKEN])
            }
            assertEquals(
                listOf("""{"authorId":"p1","reason":"Увредљиво"}""", """{"authorId":"p1"}"""),
                engine.requestHistory.map { it.body.toByteArray().decodeToString() },
            )
        }

    @Test
    fun `a refused token is FORBIDDEN and never refreshes the player's session`() =
        runTest {
            val store = storeHolding(session("a"))
            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        // Answered, so a refresh would show in the store as well as in the history.
                        WyrApi.Paths.AUTH_REFRESH -> respondSession(session("a2"))

                        else -> respondErrorDto(HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN)
                    }
                }
            val api = moderationApi(engine, store)

            val calls: List<suspend () -> Unit> =
                listOf(
                    { api.pending("not-the-token") },
                    { api.approve("not-the-token", ApproveSubmissionRequest("q1")) },
                    { api.reject("not-the-token", RejectSubmissionRequest("q1", "a duplicate")) },
                    { api.questions("not-the-token") },
                    { api.retire("not-the-token", RetireQuestionRequest("q1")) },
                    { api.restore("not-the-token", RestoreQuestionRequest("q1")) },
                    { api.reports("not-the-token") },
                    { api.dismissReports("not-the-token", DismissReportsRequest("q1")) },
                    { api.blockAuthor("not-the-token", BlockAuthorRequest("p1", "a duplicate")) },
                    { api.unblockAuthor("not-the-token", UnblockAuthorRequest("p1")) },
                    { api.deleteAccount("not-the-token", DeleteAccountRequest(accountId = "p1")) },
                )
            calls.forEach { call ->
                val failure = assertFailsWith<ApiException> { call() }
                assertEquals(ErrorCode.FORBIDDEN, failure.code)
                assertEquals(403, failure.status)
            }

            // Only the calls themselves: a 403 is not the 401 the Auth plugin refreshes on.
            assertEquals(
                listOf(
                    WyrApi.Paths.ADMIN_SUBMISSIONS,
                    WyrApi.Paths.ADMIN_APPROVALS,
                    WyrApi.Paths.ADMIN_REJECTIONS,
                    WyrApi.Paths.ADMIN_QUESTIONS,
                    WyrApi.Paths.ADMIN_RETIREMENTS,
                    WyrApi.Paths.ADMIN_RESTORATIONS,
                    WyrApi.Paths.ADMIN_REPORTS,
                    WyrApi.Paths.ADMIN_REPORT_DISMISSALS,
                    WyrApi.Paths.ADMIN_AUTHOR_BLOCKS,
                    WyrApi.Paths.ADMIN_AUTHOR_UNBLOCKS,
                    WyrApi.Paths.ADMIN_ACCOUNT_DELETIONS,
                ),
                engine.requestHistory.map { it.url.encodedPath },
            )
            assertEquals(session("a"), store.read())
        }

    @Test
    fun `the admin token goes on the admin request and on no other`() =
        runTest {
            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        WyrApi.Paths.QUESTIONS -> respondEmptyPage()
                        else -> respondOk(QUEUE)
                    }
                }
            val client = WyrHttpClient.create(BASE_URL, storeHolding(session("a")), engine)

            ModerationApi(client).pending(ADMIN_TOKEN)
            QuestionApi(client).page()

            // Set on the shared client, it would go out with every request the player makes too.
            assertEquals(
                listOf(ADMIN_TOKEN, null),
                engine.requestHistory.map { it.headers[WyrApi.Headers.ADMIN_TOKEN] },
            )
        }

    @Test
    fun `the admin token never reaches a failure's message`() =
        runTest {
            // What a server with moderation off answers: a bare 404, so the message is the client's own.
            val engine = MockEngine { respond("", HttpStatusCode.NotFound) }
            val api = moderationApi(engine, storeHolding(session("a")))

            val failure =
                assertFailsWith<ApiException> { api.reject(ADMIN_TOKEN, RejectSubmissionRequest("q1", "a duplicate")) }

            // The moderation app shows a failure's message as the server's line under it.
            assertFalse(ADMIN_TOKEN in failure.message.orEmpty(), "the admin token leaked into ${failure.message}")
        }

    private fun moderationApi(
        engine: MockEngine,
        store: SessionStore,
    ): ModerationApi = ModerationApi(WyrHttpClient.create(BASE_URL, store, engine))

    private inline fun <reified T> MockRequestHandleScope.respondOk(body: T): HttpResponseData =
        respond(WyrJson.encodeToString(body), HttpStatusCode.OK, jsonHeaders)

    private companion object {
        const val ADMIN_TOKEN = "admin-token-for-tests-only"

        val PENDING =
            SubmissionDto(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = listOf("SUPERPOWERS"),
                status = QuestionStatus.PENDING,
                submittedAt = 1_790_000_000_000L,
            )

        val QUEUE = SubmissionListDto(listOf(PENDING, PENDING.copy(id = "q2")))

        val APPROVED =
            PENDING.copy(
                categories = listOf("FOOD", "ABSURD"),
                status = QuestionStatus.APPROVED,
            )

        val REJECTED = PENDING.copy(status = QuestionStatus.REJECTED, rejectionReason = "a duplicate")

        val LISTED =
            AdminQuestionDto(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = listOf("SUPERPOWERS"),
                status = QuestionStatus.APPROVED,
                seed = false,
                submittedAt = 1_790_000_000_000L,
                reviewedAt = 1_790_000_001_000L,
                tally = VoteTallyDto(votesA = 3, votesB = 1),
                likeCount = 2,
            )

        val RETIRED = LISTED.copy(status = QuestionStatus.RETIRED, retiredAt = 1_790_000_002_000L)

        val PAGE = AdminQuestionPageDto(listOf(RETIRED, LISTED.copy(id = "seed-1", seed = true)), nextCursor = "c")

        val REPORTS =
            AdminReportListDto(
                listOf(
                    AdminReportDto(
                        question = LISTED.copy(authorId = "p1"),
                        reportCount = 3,
                        reasons =
                            listOf(
                                ReportReasonCountDto(ReportReason.OFFENSIVE, 2),
                                ReportReasonCountDto(ReportReason.SPAM, 1),
                            ),
                        lastReportedAt = 1_790_000_003_000L,
                    ),
                ),
            )
    }
}
