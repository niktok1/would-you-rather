package io.ntole.wyr.dev

import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.question.GetNextQuestion
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.question.QuestionCache
import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.domain.session.SessionDiagnostics
import io.ntole.wyr.core.domain.session.SessionInfo
import io.ntole.wyr.core.domain.session.SessionRepository
import io.ntole.wyr.core.domain.vote.CastVote
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.core.domain.vote.VoteOutcome
import io.ntole.wyr.core.domain.vote.VoteRepository
import io.ntole.wyr.core.network.trace.HttpTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DevConsoleViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val calls = mutableListOf<String>()
    private val sessions = FakeSessions(calls)
    private val diagnostics = FakeDiagnostics(sessions)
    private val queue = FakeQueue()
    private val questions = FakeQuestions(calls, queue)
    private val votes = FakeVotes(calls)

    @BeforeTest
    fun setUp() {
        // viewModelScope runs on Dispatchers.Main, which has no implementation under test.
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a finished action is logged as Ok and refreshes the header`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            testScheduler.advanceUntilIdle()
            assertEquals(null, viewModel.state.value.session)

            viewModel.ensureSession()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(listOf(LogEntry("ensureSession", "", 0, LogResult.Ok("playerId=p1"))), state.log)
            assertEquals(sessionOf("p1"), state.session)
        }

    @Test
    fun `Reset queue shows the emptied queue in the header`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            testScheduler.advanceUntilIdle()
            assertEquals(QUEUE_SIZE, viewModel.state.value.queueSize)

            viewModel.resetQueue()
            testScheduler.advanceUntilIdle()

            assertEquals(0, viewModel.state.value.queueSize)
        }

    @Test
    fun `a classified failure is logged as Err with its message`() =
        runTest(dispatcher) {
            sessions.ensure = { throw WyrException(DomainError.NETWORK, "connect timed out") }
            val viewModel = viewModel()

            viewModel.ensureSession()
            testScheduler.advanceUntilIdle()

            assertEquals(LogResult.Err(DomainError.NETWORK, "connect timed out"), viewModel.onlyResult())
        }

    @Test
    fun `anything else thrown is logged as Crash`() =
        runTest(dispatcher) {
            sessions.ensure = { error("boom") }
            val viewModel = viewModel()

            viewModel.ensureSession()
            testScheduler.advanceUntilIdle()

            assertEquals(LogResult.Crash("IllegalStateException", "boom"), viewModel.onlyResult())
        }

    @Test
    fun `a header that cannot be read keeps the action entry and is logged on its own`() =
        runTest(dispatcher) {
            // What a browser with site data blocked does: localStorage throws on every read.
            diagnostics.info = { error("storage blocked") }
            val viewModel = viewModel()

            viewModel.ensureSession()
            testScheduler.advanceUntilIdle()

            val headerFailure = LogResult.Crash("IllegalStateException", "storage blocked")
            assertEquals(
                listOf("refreshHeader" to headerFailure, "ensureSession" to LogResult.Ok("playerId=p1")),
                viewModel.log.map { it.action to it.result }.take(2),
            )
            // The one from the refresh when the console opened.
            assertEquals("refreshHeader" to headerFailure, viewModel.log.last().let { it.action to it.result })
            assertFalse(viewModel.state.value.isBusy)
        }

    @Test
    fun `cancellation is not logged as a failure`() =
        runTest(dispatcher) {
            sessions.ensure = { throw CancellationException("caller went away") }
            val viewModel = viewModel()

            viewModel.ensureSession()
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList(), viewModel.log)
            assertFalse(viewModel.state.value.isBusy)
        }

    @Test
    fun `a second action while one runs is ignored`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<Question>()
            questions.next = { answer.await() }
            val viewModel = viewModel()

            viewModel.nextQuestion()
            viewModel.ensureSession()
            testScheduler.advanceUntilIdle()
            assertTrue(viewModel.state.value.isBusy)

            answer.complete(QUESTION)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("next"), calls)
            assertFalse(viewModel.state.value.isBusy)
            assertEquals(listOf("nextQuestion"), viewModel.log.map { it.action })
        }

    @Test
    fun `New guest clears the session then resets the queue then mints a player then loads a question`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.newGuest()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("clear", "reset", "ensure", "next"), calls)
            assertEquals(QUESTION, viewModel.state.value.question)
            assertEquals(LogResult.Ok("playerId=p1 question=q1"), viewModel.onlyResult())
        }

    @Test
    fun `New guest shows the new player in the header`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.ensureSession()
            testScheduler.advanceUntilIdle()
            sessions.playerId = "p2"

            viewModel.newGuest()
            testScheduler.advanceUntilIdle()

            assertEquals(sessionOf("p2"), viewModel.state.value.session)
        }

    @Test
    fun `Skip loads the next question and sends no vote`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.nextQuestion()
            testScheduler.advanceUntilIdle()
            questions.next = { QUESTION.copy(id = "q2") }

            viewModel.skip()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("next", "next"), calls)
            assertEquals(QUESTION.copy(id = "q2"), viewModel.state.value.question)
            assertEquals("questionId=q1", viewModel.log.first().args)
        }

    @Test
    fun `a vote shows the outcome the server returned`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.nextQuestion()
            testScheduler.advanceUntilIdle()

            viewModel.vote(Side.B)
            testScheduler.advanceUntilIdle()

            assertEquals(OUTCOME.copy(yourSide = Side.B), viewModel.state.value.lastOutcome)
            assertEquals("questionId=q1 side=B", viewModel.log.first().args)
        }

    @Test
    fun `a vote by id surfaces QUESTION_NOT_FOUND and ALREADY_VOTED as Err entries`() =
        runTest(dispatcher) {
            votes.answer = { questionId, _ ->
                when (questionId) {
                    "missing" -> throw WyrException(DomainError.QUESTION_NOT_FOUND, "no such question")
                    else -> throw WyrException(DomainError.ALREADY_VOTED, "already answered")
                }
            }
            val viewModel = viewModel()

            viewModel.voteById(" missing ", Side.A)
            testScheduler.advanceUntilIdle()
            viewModel.voteById("q1", Side.A)
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf(
                    "questionId=q1 side=A" to LogResult.Err(DomainError.ALREADY_VOTED, "already answered"),
                    "questionId=missing side=A" to LogResult.Err(DomainError.QUESTION_NOT_FOUND, "no such question"),
                ),
                viewModel.log.map { it.args to it.result },
            )
        }

    @Test
    fun `the log keeps only the newest entries`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val actions = DevConsoleViewModel.LOG_CAPACITY + 5

            repeat(actions) { index ->
                sessions.playerId = "p$index"
                viewModel.ensureSession()
                testScheduler.advanceUntilIdle()
            }

            val log = viewModel.log
            assertEquals(DevConsoleViewModel.LOG_CAPACITY, log.size)
            assertEquals(LogResult.Ok("playerId=p${actions - 1}"), log.first().result)
            assertEquals(LogResult.Ok("playerId=p5"), log.last().result)
        }

    @Test
    fun `the time an action took is logged`() =
        runTest(dispatcher) {
            questions.next = {
                delay(250)
                QUESTION
            }
            val viewModel = viewModel()

            viewModel.nextQuestion()
            testScheduler.advanceUntilIdle()

            assertEquals(250, viewModel.log.single().elapsedMillis)
        }

    private fun viewModel() =
        DevConsoleViewModel(
            apiBaseUrl = "http://localhost:8080",
            sessions = sessions,
            diagnostics = diagnostics,
            questions = questions,
            queue = queue,
            getNextQuestion = GetNextQuestion(questions),
            castVote = CastVote(votes, sessions),
            httpTrace = HttpTrace(),
            // Virtual time, so an elapsed time is exactly what the fakes delayed.
            timeSource = dispatcher.scheduler.timeSource,
        )

    private val DevConsoleViewModel.log: List<LogEntry> get() = state.value.log

    private fun DevConsoleViewModel.onlyResult(): LogResult = log.single().result

    private companion object {
        const val QUEUE_SIZE = 3
        const val EXPIRY = 1_790_000_000_000L

        fun sessionOf(playerId: String) = SessionInfo(playerId, accessTokenExpiresAtEpochMillis = EXPIRY)

        val QUESTION =
            Question(
                id = "q1",
                optionA = "Fly",
                optionB = "Turn invisible",
                category = Category.SUPERPOWERS,
            )

        val OUTCOME =
            VoteOutcome(
                questionId = QUESTION.id,
                yourSide = Side.A,
                tally = Tally(votesA = 7, votesB = 3),
                pointsAwarded = 1,
                totalPoints = 42,
            )
    }

    /** Records what the use cases called, in order, into one list shared with the other fakes. */
    private class FakeSessions(
        private val calls: MutableList<String>,
    ) : SessionRepository {
        var playerId = "p1"
        var ensure: suspend () -> String = { playerId }

        /** The player the last [ensure] minted, until [clear] drops it. What the header reports. */
        var stored: String? = null
            private set

        override suspend fun ensure(): String {
            calls += "ensure"
            return ensure.invoke().also { stored = it }
        }

        override suspend fun currentPlayerId(): String = playerId

        override suspend fun clear() {
            calls += "clear"
            stored = null
        }
    }

    private class FakeQuestions(
        private val calls: MutableList<String>,
        private val queue: FakeQueue,
    ) : QuestionRepository {
        var next: suspend () -> Question = { QUESTION }

        override suspend fun next(): Question {
            calls += "next"
            return next.invoke()
        }

        override suspend fun prefetch() = Unit

        override suspend fun reset() {
            calls += "reset"
            queue.clear()
        }
    }

    private class FakeVotes(
        private val calls: MutableList<String>,
    ) : VoteRepository {
        var answer: suspend (String, Side) -> VoteOutcome = { questionId, side ->
            OUTCOME.copy(questionId = questionId, yourSide = side)
        }

        override suspend fun cast(
            questionId: String,
            side: Side,
        ): VoteOutcome {
            calls += "cast"
            return answer(questionId, side)
        }
    }

    /** Follows [FakeSessions], so the header only changes when an action changed the session. */
    private class FakeDiagnostics(
        private val sessions: FakeSessions,
    ) : SessionDiagnostics {
        var info: suspend () -> SessionInfo? = { sessions.stored?.let(::sessionOf) }

        override suspend fun info(): SessionInfo? = info.invoke()
    }

    private class FakeQueue : QuestionCache {
        private var size = QUEUE_SIZE

        override suspend fun put(questions: List<Question>) = Unit

        override suspend fun takeNext(): Question? = null

        override suspend fun count(): Int = size

        override suspend fun clear() {
            size = 0
        }
    }
}
