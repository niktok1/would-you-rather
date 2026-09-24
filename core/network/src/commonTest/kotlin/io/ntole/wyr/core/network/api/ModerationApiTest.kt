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
import io.ntole.wyr.core.network.trace.HttpTrace
import io.ntole.wyr.core.question.ApproveSubmissionRequest
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.RejectSubmissionRequest
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.question.SubmissionListDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** What each admin request carries, what it leaves to the player's session, and what it keeps out of the trace. */
class ModerationApiTest {
    private val trace = HttpTrace()

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
            val request = ApproveSubmissionRequest("q1", listOf(QuestionCategory.FOOD, QuestionCategory.RANDOM))

            val approved = moderationApi(engine, storeHolding(session("a"))).approve(ADMIN_TOKEN, request)

            assertEquals(APPROVED, approved)
            val sent = engine.requestHistory.single()
            assertEquals(HttpMethod.Post, sent.method)
            assertEquals(WyrApi.Paths.ADMIN_APPROVALS, sent.url.encodedPath)
            assertEquals(ADMIN_TOKEN, sent.headers[WyrApi.Headers.ADMIN_TOKEN])
            assertEquals(
                """{"questionId":"q1","categories":["FOOD","RANDOM"]}""",
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
                )
            calls.forEach { call ->
                val failure = assertFailsWith<ApiException> { call() }
                assertEquals(ErrorCode.FORBIDDEN, failure.code)
                assertEquals(403, failure.status)
            }

            // Only the three calls: a 403 is not the 401 the Auth plugin refreshes on.
            assertEquals(
                listOf(WyrApi.Paths.ADMIN_SUBMISSIONS, WyrApi.Paths.ADMIN_APPROVALS, WyrApi.Paths.ADMIN_REJECTIONS),
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
    fun `the admin token reaches neither the trace nor a failure's message`() =
        runTest {
            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        WyrApi.Paths.ADMIN_SUBMISSIONS -> respondOk(QUEUE)

                        WyrApi.Paths.ADMIN_APPROVALS -> respondOk(APPROVED)

                        // What a server with moderation off answers: a bare 404, so the message is the client's own.
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                }
            val api = moderationApi(engine, storeHolding(session("a")))

            api.pending(ADMIN_TOKEN)
            api.approve(ADMIN_TOKEN, ApproveSubmissionRequest("q1"))
            val failure =
                assertFailsWith<ApiException> { api.reject(ADMIN_TOKEN, RejectSubmissionRequest("q1", "a duplicate")) }

            // The dev console shows both: every exchange, and a failure's message in its log.
            val recorded = trace.exchanges.value.toString()
            assertEquals(3, trace.exchanges.value.size)
            assertFalse(ADMIN_TOKEN in recorded, "the admin token leaked into $recorded")
            assertFalse(ADMIN_TOKEN in failure.message.orEmpty(), "the admin token leaked into ${failure.message}")
        }

    private fun moderationApi(
        engine: MockEngine,
        store: SessionStore,
    ): ModerationApi = ModerationApi(WyrHttpClient.create(BASE_URL, store, engine, trace))

    private inline fun <reified T> MockRequestHandleScope.respondOk(body: T): HttpResponseData =
        respond(WyrJson.encodeToString(body), HttpStatusCode.OK, jsonHeaders)

    private companion object {
        const val ADMIN_TOKEN = "admin-token-for-tests-only"

        val PENDING =
            SubmissionDto(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = listOf(QuestionCategory.SUPERPOWERS),
                status = QuestionStatus.PENDING,
                submittedAt = 1_790_000_000_000L,
            )

        val QUEUE = SubmissionListDto(listOf(PENDING, PENDING.copy(id = "q2")))

        val APPROVED =
            PENDING.copy(
                categories = listOf(QuestionCategory.FOOD, QuestionCategory.RANDOM),
                status = QuestionStatus.APPROVED,
            )

        val REJECTED = PENDING.copy(status = QuestionStatus.REJECTED, rejectionReason = "a duplicate")
    }
}
