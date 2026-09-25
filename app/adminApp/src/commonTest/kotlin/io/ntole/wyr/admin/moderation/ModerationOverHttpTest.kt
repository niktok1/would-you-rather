package io.ntole.wyr.admin.moderation

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headers
import io.ktor.http.headersOf
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.data.moderation.DefaultModerationRepository
import io.ntole.wyr.core.domain.category.GetCategories
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.moderation.AddCategory
import io.ntole.wyr.core.domain.moderation.ApproveSubmission
import io.ntole.wyr.core.domain.moderation.GetPendingSubmissions
import io.ntole.wyr.core.domain.moderation.GetQuestions
import io.ntole.wyr.core.domain.moderation.RejectSubmission
import io.ntole.wyr.core.domain.moderation.RenameCategory
import io.ntole.wyr.core.domain.moderation.RestoreQuestion
import io.ntole.wyr.core.domain.moderation.RetireQuestion
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.core.network.InMemoryTokenStorage
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.WyrJson
import io.ntole.wyr.core.network.api.ModerationApi
import io.ntole.wyr.core.network.environment.WyrEnvironment
import io.ntole.wyr.core.question.AdminQuestionDto
import io.ntole.wyr.core.question.AdminQuestionPageDto
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.question.SubmissionListDto
import io.ntole.wyr.core.vote.VoteTallyDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The app over the real client configuration, answered as the server answers an admin route: what
 * each refusal reads as on the screen, from the HTTP status it came with.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModerationOverHttpTest {
    private val dispatcher = StandardTestDispatcher()
    private val requests = mutableListOf<HttpRequestData>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a 403 reads as a wrong token`() =
        runTest(dispatcher) {
            val viewModel =
                openOver { respondError(HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN, "wrong admin token") }

            val failure = loadPending(viewModel)

            assertEquals(Failure.Refused(DomainError.FORBIDDEN, detail = "wrong admin token"), failure)
            assertTrue("Wrong admin token" in describe(failure as Failure))
        }

    @Test
    fun `a bare 404 reads as moderation off on that server`() =
        runTest(dispatcher) {
            // What a server without ADMIN_TOKEN answers: it registered no admin route.
            val viewModel = openOver { respond("", HttpStatusCode.NotFound) }

            val failure = loadPending(viewModel)

            assertEquals(DomainError.UNKNOWN, (failure as Failure.Refused).error)
            assertTrue("A bare 404 means moderation is off on this server" in describe(failure))
            assertTrue("404" in failure.detail.orEmpty(), failure.detail)
        }

    @Test
    fun `an answer that is not the server's claims no status and shows the one it came with`() =
        runTest(dispatcher) {
            // A proxy's own page, which no ErrorDto names: it once read as moderation off, with a 404.
            val html = headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString())
            val viewModel = openOver { respond("<html>blocked</html>", HttpStatusCode.Forbidden, html) }

            val failure = loadPending(viewModel) as Failure.Refused

            assertEquals(DomainError.UNKNOWN, failure.error)
            // No status of its own in the headline, which once said "(404)" of every such answer.
            assertFalse("(404)" in describe(failure), describe(failure))
            assertTrue("403" in failure.detail.orEmpty(), failure.detail)
        }

    @Test
    fun `a 409 on a decision shows under it and the queue is read again`() =
        runTest(dispatcher) {
            val viewModel =
                openOver { request ->
                    when (request.url.encodedPath) {
                        WyrApi.Paths.ADMIN_APPROVALS -> {
                            respondError(
                                HttpStatusCode.Conflict,
                                ErrorCode.ALREADY_DECIDED,
                                "decided",
                            )
                        }

                        else -> {
                            respondQueue()
                        }
                    }
                }
            loadPending(viewModel)

            viewModel.approve("q1", Screen.PENDING)
            settle(viewModel)

            val failed = viewModel.state.value.pending.outcomes.failures["q1"]
            assertEquals(
                ItemFailure("\"Fly\" or \"Swim\"", Failure.Refused(DomainError.ALREADY_DECIDED, detail = "decided")),
                failed,
            )
            assertEquals(
                listOf(WyrApi.Paths.ADMIN_SUBMISSIONS, WyrApi.Paths.ADMIN_APPROVALS, WyrApi.Paths.ADMIN_SUBMISSIONS),
                requests.map { it.url.encodedPath },
            )
        }

    @Test
    fun `a 409 on a retirement shows under the question and the list is read again`() =
        runTest(dispatcher) {
            val viewModel =
                openOver { request ->
                    when (request.url.encodedPath) {
                        WyrApi.Paths.ADMIN_RETIREMENTS -> {
                            respondError(HttpStatusCode.Conflict, ErrorCode.WRONG_STATUS, "not approved")
                        }

                        else -> {
                            respondList()
                        }
                    }
                }
            viewModel.loadQuestions()
            settle(viewModel)

            viewModel.askToRetire("seed-1")
            viewModel.confirmRetire()
            settle(viewModel)

            val failed = viewModel.state.value.questions.outcomes.failures["seed-1"]
            assertEquals(
                ItemFailure("\"Cats\" or \"Dogs\"", Failure.Refused(DomainError.WRONG_STATUS, detail = "not approved")),
                failed,
            )
            assertEquals(
                listOf(WyrApi.Paths.ADMIN_QUESTIONS, WyrApi.Paths.ADMIN_RETIREMENTS, WyrApi.Paths.ADMIN_QUESTIONS),
                requests.map { it.url.encodedPath },
            )
        }

    @Test
    fun `a 429 reads with the wait its Retry-After names`() =
        runTest(dispatcher) {
            val viewModel =
                openOver {
                    respond(
                        WyrJson.encodeToString(
                            ErrorDto(message = "too many requests; retry in 42 s", code = ErrorCode.RATE_LIMITED),
                        ),
                        HttpStatusCode.TooManyRequests,
                        headers {
                            append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            append(HttpHeaders.RetryAfter, "42")
                        },
                    )
                }

            val failure = loadPending(viewModel)

            assertEquals(42.seconds, (failure as Failure.Refused).retryAfter)
            assertTrue("try again in 42 s" in describe(failure), describe(failure))
        }

    @Test
    fun `every request carries the admin token and nothing of a player's`() =
        runTest(dispatcher) {
            val viewModel = openOver { respondQueue() }

            assertNull(loadPending(viewModel))
            viewModel.approve("q1", Screen.PENDING)
            settle(viewModel)

            assertEquals(3, requests.size)
            requests.forEach { request ->
                assertEquals(FakeModeration.TOKEN, request.headers[WyrApi.Headers.ADMIN_TOKEN])
                assertNull(request.headers[HttpHeaders.Authorization], request.url.toString())
            }
        }

    private fun TestScope.openOver(
        answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): ModerationViewModel {
        val engine =
            MockEngine(
                MockEngineConfig().apply {
                    // On the test's scheduler, so the ViewModel's work and the exchange run as one.
                    dispatcher = this@ModerationOverHttpTest.dispatcher
                    addHandler { request ->
                        requests += request
                        answer(request)
                    }
                },
            )
        val client = WyrHttpClient.create(BASE_URL, SessionStore(InMemoryTokenStorage(), WyrEnvironment.LOCAL), engine)
        val repository = DefaultModerationRepository(ModerationApi(client))
        return ModerationViewModel(
            getPendingSubmissions = GetPendingSubmissions(repository),
            approveSubmission = ApproveSubmission(repository),
            rejectSubmission = RejectSubmission(repository),
            getQuestions = GetQuestions(repository),
            retireQuestion = RetireQuestion(repository),
            restoreQuestion = RestoreQuestion(repository),
            // Not over the engine: this is about the admin routes, and the categories are none.
            getCategories = GetCategories(FakeCategories()),
            addCategory = AddCategory(repository),
            renameCategory = RenameCategory(repository),
        ).also {
            it.setAdminToken(FakeModeration.TOKEN)
            testScheduler.advanceUntilIdle()
        }
    }

    /** Loads the queue and answers how its read failed, or `null` when it worked. */
    private suspend fun TestScope.loadPending(viewModel: ModerationViewModel): Failure? {
        viewModel.loadPending()
        settle(viewModel)
        return viewModel.state.value.pending.failure
    }

    private suspend fun TestScope.settle(viewModel: ModerationViewModel) {
        testScheduler.advanceUntilIdle()
        viewModel.state.first { !it.isBusy }
    }

    private fun MockRequestHandleScope.respondQueue(): HttpResponseData {
        val submission =
            SubmissionDto(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = listOf("SUPERPOWERS"),
                status = QuestionStatus.PENDING,
                submittedAt = 1_790_000_000_000L,
            )
        return respond(WyrJson.encodeToString(SubmissionListDto(listOf(submission))), HttpStatusCode.OK, JSON)
    }

    private fun MockRequestHandleScope.respondList(): HttpResponseData {
        val seed =
            AdminQuestionDto(
                id = "seed-1",
                optionA = "Cats",
                optionB = "Dogs",
                categories = listOf("ABSURD"),
                status = QuestionStatus.APPROVED,
                seed = true,
                submittedAt = 1_790_000_000_000L,
                tally = VoteTallyDto(votesA = 3, votesB = 1),
                likeCount = 2,
            )
        return respond(WyrJson.encodeToString(AdminQuestionPageDto(listOf(seed))), HttpStatusCode.OK, JSON)
    }

    private fun MockRequestHandleScope.respondError(
        status: HttpStatusCode,
        code: ErrorCode,
        message: String,
    ): HttpResponseData = respond(WyrJson.encodeToString(ErrorDto(message = message, code = code)), status, JSON)

    private companion object {
        const val BASE_URL = "https://wyr.test"
        val JSON = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    }
}
