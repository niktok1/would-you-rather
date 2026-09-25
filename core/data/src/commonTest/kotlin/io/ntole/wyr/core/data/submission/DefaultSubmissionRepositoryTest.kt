package io.ntole.wyr.core.data.submission

import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.data.BASE_URL
import io.ntole.wyr.core.data.FakeServer
import io.ntole.wyr.core.data.session
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.storeHolding
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.GetMySubmissions
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.core.domain.submission.SubmitQuestion
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.api.AuthApi
import io.ntole.wyr.core.network.api.SubmissionApi
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.SubmitQuestionRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

/** Submitting and listing through the real client, session recovery included, against [FakeServer]. */
class DefaultSubmissionRepositoryTest {
    private val server = FakeServer()

    @Test
    fun `a first launch's submission goes out once with the session just minted`() =
        runTest {
            val store = storeHolding(null)
            val client = WyrHttpClient.create(BASE_URL, store, server.engine)
            val sessions = DefaultSessionRepository(AuthApi(client), store)
            val submitQuestion = SubmitQuestion(DefaultSubmissionRepository(SubmissionApi(client), sessions), sessions)

            val stored = submitQuestion("Fly", "Swim", setOf(Category.SUPERPOWERS, Category.FOOD))

            // FakeServer's answer, decoded through the real client and mapped.
            assertEquals(
                Submission(
                    id = "s1",
                    optionA = "Fly",
                    optionB = "Swim",
                    categories = setOf(Category.FOOD, Category.SUPERPOWERS),
                    status = SubmissionStatus.PENDING,
                    rejectionReason = null,
                    submittedAt = Instant.fromEpochMilliseconds(FakeServer.SUBMITTED_AT),
                ),
                stored,
            )
            // Not refused and then recovered: the session was ensured before the question was sent.
            assertEquals(requestsSentAs("guest1"), server.submissionsSentAs)
        }

    @Test
    fun `a submission on a dead session is sent again as a fresh guest`() =
        runTest {
            // The server has never heard of "a": the state after a dev server restarts.
            val store = storeHolding(session("a"))

            val stored = repositoryOver(store).submit("Fly", "Swim", setOf(Category.FOOD, Category.SUPERPOWERS))

            assertEquals("s1", stored.id)
            assertEquals(1, server.guestsMinted)
            assertEquals("guest1", store.read()?.playerId)
            // The same submission both times, now the new guest's: the 401 stored nothing.
            assertEquals(requestsSentAs("a", "guest1"), server.submissionsSentAs)
        }

    @Test
    fun `the author's submissions are listed newest first as the server sent them`() =
        runTest {
            val store = storeHolding(null)
            val client = WyrHttpClient.create(BASE_URL, store, server.engine)
            val sessions = DefaultSessionRepository(AuthApi(client), store)
            val getMySubmissions =
                GetMySubmissions(DefaultSubmissionRepository(SubmissionApi(client), sessions), sessions)

            val mine = getMySubmissions()

            assertEquals(
                listOf(
                    Submission(
                        id = "s2",
                        optionA = "s2-a",
                        optionB = "s2-b",
                        categories = setOf(Category.ETHICS),
                        status = SubmissionStatus.REJECTED,
                        rejectionReason = "a duplicate",
                        submittedAt = Instant.fromEpochMilliseconds(FakeServer.SUBMITTED_AT + 1),
                    ),
                    Submission(
                        id = "s1",
                        optionA = "s1-a",
                        optionB = "s1-b",
                        categories = setOf(Category.FOOD, Category.RANDOM),
                        status = SubmissionStatus.PENDING,
                        rejectionReason = null,
                        submittedAt = Instant.fromEpochMilliseconds(FakeServer.SUBMITTED_AT),
                    ),
                ),
                mine,
            )
            assertEquals(listOf<String?>("Bearer access-guest1"), server.submissionListsSentAs)
        }

    @Test
    fun `the author's submissions read on a dead session are read again as a fresh guest`() =
        runTest {
            val store = storeHolding(session("a"))

            val mine = repositoryOver(store).mine()

            assertEquals(listOf("s2", "s1"), mine.map { it.id }, "the fresh guest's list, not the dead session's")
            assertEquals(1, server.guestsMinted)
            assertEquals("guest1", store.read()?.playerId)
            assertEquals(listOf<String?>("Bearer access-a", "Bearer access-guest1"), server.submissionListsSentAs)
        }

    @Test
    fun `options the server's rules refuse are INVALID_SUBMISSION with the server's own message`() =
        runTest {
            server.refuseSubmissionsWith =
                HttpStatusCode.UnprocessableEntity to ErrorDto("optionA is blank", ErrorCode.INVALID_SUBMISSION)
            val store = storeHolding(session("a"))

            val failure =
                assertFailsWith<WyrException> {
                    repositoryOver(store).submit(" ", "Swim", setOf(Category.FOOD))
                }

            assertEquals(DomainError.INVALID_SUBMISSION, failure.error)
            // The server's diagnostic text, carried as the message and never shown to the player.
            assertEquals("optionA is blank", failure.message)
            // Sent once and left alone: the player's to put right, not a dead session to recover.
            assertEquals(1, server.submissionsSentAs.size)
            assertEquals(0, server.guestsMinted)
            assertEquals(session("a"), store.read())
        }

    @Test
    fun `one pending submission too many is SUBMISSION_LIMIT`() =
        runTest {
            server.refuseSubmissionsWith =
                HttpStatusCode.Conflict to ErrorDto("20 submissions pending", ErrorCode.SUBMISSION_LIMIT)
            val store = storeHolding(session("a"))

            val failure =
                assertFailsWith<WyrException> {
                    repositoryOver(store).submit("Fly", "Swim", setOf(Category.FOOD))
                }

            assertEquals(DomainError.SUBMISSION_LIMIT, failure.error)
            assertEquals("20 submissions pending", failure.message)
            assertEquals(1, server.submissionsSentAs.size)
            assertEquals(0, server.guestsMinted)
        }

    @Test
    fun `a submission under no category or under OTHER is refused before anything is sent`() =
        runTest {
            val submissions = repositoryOver(storeHolding(session("a")))

            listOf(emptySet(), setOf(Category.OTHER), setOf(Category.FOOD, Category.OTHER)).forEach { categories ->
                assertFailsWith<IllegalArgumentException>("$categories") {
                    submissions.submit("Fly", "Swim", categories)
                }
            }

            // Not even a guest: nothing left the client.
            assertEquals(emptyList(), server.engine.requestHistory)
        }

    private fun repositoryOver(store: SessionStore): DefaultSubmissionRepository {
        val client = WyrHttpClient.create(BASE_URL, store, server.engine)
        return DefaultSubmissionRepository(SubmissionApi(client), DefaultSessionRepository(AuthApi(client), store))
    }

    /** [REQUEST] sent once as each of [players] in turn, as [FakeServer.submissionsSentAs] records it. */
    private fun requestsSentAs(vararg players: String): List<Pair<String?, SubmitQuestionRequest>> =
        players.map { player -> "Bearer access-$player" to REQUEST }

    private companion object {
        /** What submitting "Fly" / "Swim" under FOOD and SUPERPOWERS sends, in whatever order they were picked. */
        val REQUEST =
            SubmitQuestionRequest(
                optionA = "Fly",
                optionB = "Swim",
                categories = listOf(QuestionCategory.FOOD, QuestionCategory.SUPERPOWERS),
            )
    }
}
