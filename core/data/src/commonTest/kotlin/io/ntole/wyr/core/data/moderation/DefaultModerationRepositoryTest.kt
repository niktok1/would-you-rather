package io.ntole.wyr.core.data.moderation

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.data.BASE_URL
import io.ntole.wyr.core.data.respondJson
import io.ntole.wyr.core.data.session
import io.ntole.wyr.core.data.storeHolding
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.moderation.AdminToken
import io.ntole.wyr.core.domain.moderation.RejectionReason
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.WyrJson
import io.ntole.wyr.core.network.api.ModerationApi
import io.ntole.wyr.core.question.ApproveSubmissionRequest
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.RejectSubmissionRequest
import io.ntole.wyr.core.question.SubmissionDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.time.Instant

/** Moderating through the real client, against just enough of the admin routes behind a [MockEngine]. */
class DefaultModerationRepositoryTest {
    private val token = assertNotNull(AdminToken.of("admin-token-for-tests-only"))

    /** When set, every admin request is answered with it, whatever it carries. */
    private var refuseWith: (MockRequestHandleScope.() -> HttpResponseData)? = null

    private val engine = MockEngine { request -> answer(request) }

    @Test
    fun `the pending queue is read with the token and listed as each author sees their submission`() =
        runTest {
            val pending = repositoryOver(storeHolding(session("a"))).pending(token)

            assertEquals(
                listOf(
                    Submission(
                        id = "q1",
                        optionA = "Fly",
                        optionB = "Swim",
                        categories = setOf(Category.SUPERPOWERS),
                        status = SubmissionStatus.PENDING,
                        rejectionReason = null,
                        submittedAt = Instant.fromEpochMilliseconds(SUBMITTED_AT),
                    ),
                    // A category this build cannot name, beside one it can, as for the author's own list.
                    Submission(
                        id = "q2",
                        optionA = "Tea",
                        optionB = "Coffee",
                        categories = setOf(Category.FOOD, Category.OTHER),
                        status = SubmissionStatus.PENDING,
                        rejectionReason = null,
                        submittedAt = Instant.fromEpochMilliseconds(SUBMITTED_AT + 1),
                    ),
                ),
                pending,
            )
            assertEquals(listOf(token.value), adminTokensSent())
        }

    @Test
    fun `an approval sends its categories in declaration order and comes back filed under them`() =
        runTest {
            val approved =
                repositoryOver(storeHolding(session("a")))
                    .approve(token, "q1", setOf(Category.RANDOM, Category.FOOD))

            assertEquals(SubmissionStatus.APPROVED, approved.status)
            assertEquals(setOf(Category.FOOD, Category.RANDOM), approved.categories)
            assertEquals(
                ApproveSubmissionRequest("q1", listOf(QuestionCategory.FOOD, QuestionCategory.RANDOM)),
                decode<ApproveSubmissionRequest>(engine.requestHistory.single()),
            )
            assertEquals(listOf(token.value), adminTokensSent())
        }

    @Test
    fun `an approval under no categories keeps the author's`() =
        runTest {
            val approved = repositoryOver(storeHolding(session("a"))).approve(token, "q1", emptySet())

            assertEquals(setOf(Category.SUPERPOWERS), approved.categories)
            val sent = decode<ApproveSubmissionRequest>(engine.requestHistory.single())
            assertEquals(ApproveSubmissionRequest("q1"), sent)
        }

    @Test
    fun `an approval under OTHER sends nothing`() =
        runTest {
            val moderation = repositoryOver(storeHolding(session("a")))

            assertFailsWith<IllegalArgumentException> { moderation.approve(token, "q1", setOf(Category.OTHER)) }

            assertEquals(emptyList(), engine.requestHistory)
        }

    @Test
    fun `a rejection sends its reason trimmed and comes back with it`() =
        runTest {
            val reason = assertNotNull(RejectionReason.of("  Too close to a seed "))

            val rejected = repositoryOver(storeHolding(session("a"))).reject(token, "q1", reason)

            assertEquals(SubmissionStatus.REJECTED, rejected.status)
            assertEquals("Too close to a seed", rejected.rejectionReason)
            assertEquals(
                RejectSubmissionRequest("q1", "Too close to a seed"),
                decode<RejectSubmissionRequest>(engine.requestHistory.single()),
            )
            assertEquals(listOf(token.value), adminTokensSent())
        }

    @Test
    fun `each refusal of a decision reads as its own DomainError with the server's message`() =
        runTest {
            val moderation = repositoryOver(storeHolding(session("a")))
            val reason = assertNotNull(RejectionReason.of("a duplicate"))
            val table =
                listOf(
                    Triple(HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN, DomainError.FORBIDDEN),
                    Triple(HttpStatusCode.Conflict, ErrorCode.ALREADY_DECIDED, DomainError.ALREADY_DECIDED),
                    Triple(HttpStatusCode.NotFound, ErrorCode.QUESTION_NOT_FOUND, DomainError.QUESTION_NOT_FOUND),
                    // Only a client bug sends a decision the server's rules refuse.
                    Triple(HttpStatusCode.BadRequest, ErrorCode.VALIDATION_FAILED, DomainError.SERVER),
                )

            table.forEach { (status, code, expected) ->
                refuseWith = { respondError(status, ErrorDto("refused as $code", code)) }
                val decisions: List<suspend () -> Unit> =
                    listOf(
                        { moderation.pending(token) },
                        { moderation.approve(token, "q1", emptySet()) },
                        { moderation.reject(token, "q1", reason) },
                    )

                decisions.forEach { decision ->
                    val failure = assertFailsWith<WyrException>("$code") { decision() }
                    assertEquals(expected, failure.error, "$code")
                    // What the dev console shows beside the error.
                    assertEquals("refused as $code", failure.message)
                }
            }
        }

    @Test
    fun `a server with moderation off answers as a path it does not have`() =
        runTest {
            // No ADMIN_TOKEN, so no admin routes: a bare 404, with no ErrorDto to name a cause.
            refuseWith = { respond("", HttpStatusCode.NotFound) }

            val failure = assertFailsWith<WyrException> { repositoryOver(storeHolding(session("a"))).pending(token) }

            assertEquals(DomainError.UNKNOWN, failure.error)
        }

    @Test
    fun `a refused token leaves the player's session as it was`() =
        runTest {
            val store = storeHolding(session("a"))
            val moderation = repositoryOver(store)
            refuseWith = { respondError(HttpStatusCode.Forbidden, ErrorDto("wrong token", ErrorCode.FORBIDDEN)) }

            assertFailsWith<WyrException> { moderation.pending(token) }
            assertFailsWith<WyrException> { moderation.approve(token, "q1", emptySet()) }

            // Neither refreshed nor replaced: no request but the two, and the same session stored.
            assertEquals(
                listOf(WyrApi.Paths.ADMIN_SUBMISSIONS, WyrApi.Paths.ADMIN_APPROVALS),
                engine.requestHistory.map { it.url.encodedPath },
            )
            assertEquals(session("a"), store.read())
        }

    @Test
    fun `a 401 from an admin route never replaces the player's session`() =
        runTest {
            // The server never sends one there; a proxy might. The Auth plugin refreshes the player's
            // session, as it does on every 401, but nothing mints a guest in its place.
            val store = storeHolding(session("a"))
            refuseWith = { respondError(HttpStatusCode.Unauthorized, ErrorDto("not here", ErrorCode.UNAUTHORIZED)) }

            val failure = assertFailsWith<WyrException> { repositoryOver(store).pending(token) }

            assertEquals(DomainError.UNAUTHORIZED, failure.error)
            assertEquals(0, engine.requestHistory.count { it.url.encodedPath == WyrApi.Paths.AUTH_GUEST })
            assertEquals(ROTATED, store.read())
        }

    @Test
    fun `a moderator with no player session is never given one`() =
        runTest {
            val store = storeHolding(null)

            repositoryOver(store).pending(token)

            assertEquals(listOf(WyrApi.Paths.ADMIN_SUBMISSIONS), engine.requestHistory.map { it.url.encodedPath })
            assertEquals(null, store.read())
        }

    private suspend fun MockRequestHandleScope.answer(request: HttpRequestData): HttpResponseData {
        val refusal = refuseWith
        return when {
            request.url.encodedPath == WyrApi.Paths.AUTH_REFRESH -> {
                respondJson(WyrJson.encodeToString(ROTATED))
            }

            request.url.encodedPath == WyrApi.Paths.AUTH_GUEST -> {
                respondJson(WyrJson.encodeToString(session("guest1")))
            }

            refusal != null -> {
                refusal(this)
            }

            request.url.encodedPath == WyrApi.Paths.ADMIN_SUBMISSIONS -> {
                respondJson(QUEUE_JSON)
            }

            request.url.encodedPath == WyrApi.Paths.ADMIN_APPROVALS -> {
                val approval = decode<ApproveSubmissionRequest>(request)
                val categories = approval.categories.ifEmpty { PENDING.categories }
                respondJson(
                    WyrJson.encodeToString(PENDING.copy(categories = categories, status = QuestionStatus.APPROVED)),
                )
            }

            request.url.encodedPath == WyrApi.Paths.ADMIN_REJECTIONS -> {
                // Echoed as sent, so a reason sent untrimmed would come back untrimmed.
                val rejection = decode<RejectSubmissionRequest>(request)
                respondJson(
                    WyrJson.encodeToString(
                        PENDING.copy(status = QuestionStatus.REJECTED, rejectionReason = rejection.reason),
                    ),
                )
            }

            else -> {
                error("no route for ${request.url}")
            }
        }
    }

    private fun MockRequestHandleScope.respondError(
        status: HttpStatusCode,
        error: ErrorDto,
    ): HttpResponseData =
        respond(
            WyrJson.encodeToString(error),
            status,
            headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )

    private suspend inline fun <reified T> decode(request: HttpRequestData): T =
        WyrJson.decodeFromString(request.body.toByteArray().decodeToString())

    private fun adminTokensSent(): List<String?> = engine.requestHistory.map { it.headers[WyrApi.Headers.ADMIN_TOKEN] }

    private fun repositoryOver(store: SessionStore): DefaultModerationRepository =
        DefaultModerationRepository(ModerationApi(WyrHttpClient.create(BASE_URL, store, engine)))

    private companion object {
        const val SUBMITTED_AT = 1_790_000_000_000L

        /** A refresh of "a"'s session, as the server rotates it: still player "a". */
        val ROTATED = session("a").copy(accessToken = "access-a-rotated", refreshToken = "refresh-a-rotated")

        val PENDING =
            SubmissionDto(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = listOf(QuestionCategory.SUPERPOWERS),
                status = QuestionStatus.PENDING,
                submittedAt = SUBMITTED_AT,
            )

        // A literal, so a category name this build has never heard of can be in it.
        val QUEUE_JSON =
            """{"submissions":[""" +
                """{"id":"q1","optionA":"Fly","optionB":"Swim","categories":["SUPERPOWERS"],""" +
                """"status":"PENDING","submittedAt":$SUBMITTED_AT},""" +
                """{"id":"q2","optionA":"Tea","optionB":"Coffee","categories":["CATEGORY_FROM_THE_FUTURE","FOOD"],""" +
                """"status":"PENDING","submittedAt":${SUBMITTED_AT + 1}}""" +
                """]}"""
    }
}
