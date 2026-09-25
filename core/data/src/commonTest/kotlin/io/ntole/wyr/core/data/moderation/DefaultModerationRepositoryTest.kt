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
import io.ntole.wyr.core.domain.moderation.ModeratedQuestion
import io.ntole.wyr.core.domain.moderation.ModeratedQuestionPage
import io.ntole.wyr.core.domain.moderation.ModerationRepository
import io.ntole.wyr.core.domain.moderation.QuestionCursor
import io.ntole.wyr.core.domain.moderation.QuestionFilter
import io.ntole.wyr.core.domain.moderation.RejectionReason
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.WyrJson
import io.ntole.wyr.core.network.api.ModerationApi
import io.ntole.wyr.core.question.AdminQuestionDto
import io.ntole.wyr.core.question.ApproveSubmissionRequest
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.RejectSubmissionRequest
import io.ntole.wyr.core.question.RestoreQuestionRequest
import io.ntole.wyr.core.question.RetireQuestionRequest
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.vote.VoteTallyDto
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
            // As many as the server lists at once, so a queue longer than its default page is read whole.
            assertEquals(
                "${ModerationRepository.PAGE_SIZE}",
                engine.requestHistory
                    .single()
                    .url.parameters[WyrApi.Query.LIMIT],
            )
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
                        { moderation.questions(token, QuestionFilter(), after = null) },
                        { moderation.retire(token, "q1") },
                        { moderation.restore(token, "q1") },
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
            // The server never sends one there. This one carries its ErrorDto, so it reads as a dead
            // session, as a proxy's bare 401 would not. The Auth plugin refreshes the player's
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

            val moderation = repositoryOver(store)
            moderation.pending(token)
            moderation.questions(token, QuestionFilter(), after = null)
            moderation.retire(token, "q1")
            moderation.restore(token, "q1")

            assertEquals(
                listOf(
                    WyrApi.Paths.ADMIN_SUBMISSIONS,
                    WyrApi.Paths.ADMIN_QUESTIONS,
                    WyrApi.Paths.ADMIN_RETIREMENTS,
                    WyrApi.Paths.ADMIN_RESTORATIONS,
                ),
                engine.requestHistory.map { it.url.encodedPath },
            )
            assertEquals(listOf(null), engine.requestHistory.map { it.headers[HttpHeaders.Authorization] }.distinct())
            assertEquals(null, store.read())
        }

    @Test
    fun `every question is read a page at a time with the filter by its wire names and the cursor as given`() =
        runTest {
            val filter =
                QuestionFilter(
                    statuses = setOf(SubmissionStatus.RETIRED, SubmissionStatus.PENDING),
                    categories = setOf(Category.RANDOM, Category.FOOD),
                )

            val page = repositoryOver(storeHolding(session("a"))).questions(token, filter, QuestionCursor("c1"))

            val sent = engine.requestHistory.single()
            assertEquals(WyrApi.Paths.ADMIN_QUESTIONS, sent.url.encodedPath)
            assertEquals(listOf("PENDING", "RETIRED"), sent.url.parameters.getAll(WyrApi.Query.STATUS))
            assertEquals(listOf("FOOD", "RANDOM"), sent.url.parameters.getAll(WyrApi.Query.CATEGORY))
            assertEquals("c1", sent.url.parameters[WyrApi.Query.CURSOR])
            assertEquals("${ModerationRepository.PAGE_SIZE}", sent.url.parameters[WyrApi.Query.LIMIT])
            assertEquals(listOf(token.value), adminTokensSent())
            assertEquals(
                ModeratedQuestionPage(
                    questions =
                        listOf(
                            LISTED_RETIRED,
                            // A seed, filed under a category this build cannot name and at a status it
                            // cannot name either: still listed, as OTHER.
                            LISTED_RETIRED.copy(
                                id = "seed-1",
                                categories = setOf(Category.FOOD, Category.OTHER),
                                status = SubmissionStatus.OTHER,
                                isSeed = true,
                                reviewedAt = null,
                                retiredAt = null,
                            ),
                        ),
                    next = QuestionCursor("next-page"),
                ),
                page,
            )
        }

    @Test
    fun `the first page is asked for with no filter and no cursor`() =
        runTest {
            repositoryOver(storeHolding(null)).questions(token, QuestionFilter(), after = null)

            val parameters =
                engine.requestHistory
                    .single()
                    .url.parameters
            assertEquals(setOf(WyrApi.Query.LIMIT), parameters.names())
        }

    @Test
    fun `a filter by what this build cannot name sends nothing`() =
        runTest {
            val moderation = repositoryOver(storeHolding(session("a")))

            listOf(
                QuestionFilter(statuses = setOf(SubmissionStatus.APPROVED, SubmissionStatus.OTHER)),
                QuestionFilter(categories = setOf(Category.FOOD, Category.OTHER)),
            ).forEach { filter ->
                assertFailsWith<IllegalArgumentException>(
                    "$filter",
                ) { moderation.questions(token, filter, after = null) }
            }

            assertEquals(emptyList(), engine.requestHistory)
        }

    @Test
    fun `a retirement and a restoration send the question's id and come back as the list shows it`() =
        runTest {
            val moderation = repositoryOver(storeHolding(session("a")))

            assertEquals(LISTED_RETIRED, moderation.retire(token, "q1"))
            assertEquals(
                LISTED_RETIRED.copy(status = SubmissionStatus.APPROVED, retiredAt = null),
                moderation.restore(token, "q1"),
            )

            assertEquals(
                listOf(WyrApi.Paths.ADMIN_RETIREMENTS, WyrApi.Paths.ADMIN_RESTORATIONS),
                engine.requestHistory.map { it.url.encodedPath },
            )
            assertEquals(RetireQuestionRequest("q1"), decode<RetireQuestionRequest>(engine.requestHistory[0]))
            assertEquals(RestoreQuestionRequest("q1"), decode<RestoreQuestionRequest>(engine.requestHistory[1]))
            assertEquals(listOf(token.value, token.value), adminTokensSent())
        }

    @Test
    fun `a retirement or restoration of a question at the wrong status reads as WRONG_STATUS`() =
        runTest {
            val moderation = repositoryOver(storeHolding(session("a")))
            refuseWith = { respondError(HttpStatusCode.Conflict, ErrorDto("not approved", ErrorCode.WRONG_STATUS)) }

            listOf<suspend () -> Unit>({ moderation.retire(token, "q1") }, { moderation.restore(token, "q1") })
                .forEach { move ->
                    val failure = assertFailsWith<WyrException> { move() }
                    assertEquals(DomainError.WRONG_STATUS, failure.error)
                    assertEquals("not approved", failure.message)
                }
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

            request.url.encodedPath == WyrApi.Paths.ADMIN_QUESTIONS -> {
                respondJson(PAGE_JSON)
            }

            request.url.encodedPath == WyrApi.Paths.ADMIN_RETIREMENTS -> {
                respondJson(WyrJson.encodeToString(RETIRED))
            }

            request.url.encodedPath == WyrApi.Paths.ADMIN_RESTORATIONS -> {
                respondJson(WyrJson.encodeToString(RETIRED.copy(status = QuestionStatus.APPROVED, retiredAt = null)))
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

        val RETIRED =
            AdminQuestionDto(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = listOf(QuestionCategory.SUPERPOWERS),
                status = QuestionStatus.RETIRED,
                seed = false,
                submittedAt = SUBMITTED_AT,
                reviewedAt = SUBMITTED_AT + 1_000,
                retiredAt = SUBMITTED_AT + 2_000,
                tally = VoteTallyDto(votesA = 3, votesB = 1),
                likeCount = 2,
            )

        /** [RETIRED], as the domain holds it. */
        val LISTED_RETIRED =
            ModeratedQuestion(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = setOf(Category.SUPERPOWERS),
                status = SubmissionStatus.RETIRED,
                isSeed = false,
                submittedAt = Instant.fromEpochMilliseconds(SUBMITTED_AT),
                reviewedAt = Instant.fromEpochMilliseconds(SUBMITTED_AT + 1_000),
                retiredAt = Instant.fromEpochMilliseconds(SUBMITTED_AT + 2_000),
                rejectionReason = null,
                tally = Tally(votesA = 3, votesB = 1),
                likeCount = 2,
            )

        // A literal, so a category and a status this build has never heard of can be in it.
        val PAGE_JSON =
            """{"questions":[""" + WyrJson.encodeToString(RETIRED) + "," +
                """{"id":"seed-1","optionA":"Fly","optionB":"Swim",""" +
                """"categories":["CATEGORY_FROM_THE_FUTURE","FOOD"],"status":"STATUS_FROM_THE_FUTURE",""" +
                """"seed":true,"submittedAt":$SUBMITTED_AT,"reviewedAt":null,""" +
                """"retiredAt":null,"rejectionReason":null,"tally":{"votesA":3,"votesB":1},"likeCount":2}""" +
                """],"nextCursor":"next-page"}"""

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
