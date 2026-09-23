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
import io.ntole.wyr.core.domain.vote.AttemptId
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
import kotlin.test.assertIs
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

            assertEquals(listOf("ensure", "next"), calls)
            assertFalse(viewModel.state.value.isBusy)
            assertEquals(listOf("nextQuestion"), viewModel.log.map { it.action })
        }

    @Test
    fun `New guest clears the session then resets the queue then mints a player then loads a question`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.newGuest()
            testScheduler.advanceUntilIdle()

            // The second ensure is GetNextQuestion making sure of the session it just got.
            assertEquals(listOf("clear", "reset", "ensure", "ensure", "next"), calls)
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

            assertEquals(listOf("ensure", "next", "ensure", "next"), calls)
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
            assertEquals("questionId=q1 side=B attempt=${votes.attempts.single().value}", viewModel.log.first().args)
        }

    @Test
    fun `a question the feed looped back to is labelled in the log`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.nextQuestion()
            testScheduler.advanceUntilIdle()
            questions.next = { QUESTION.copy(answeredBefore = true) }

            viewModel.nextQuestion()
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf(LogResult.Ok("question=q1 looped"), LogResult.Ok("question=q1")),
                viewModel.log.map { it.result },
            )
        }

    @Test
    fun `a replayed vote says so in the log`() =
        runTest(dispatcher) {
            votes.answer = { questionId, side ->
                OUTCOME.copy(questionId = questionId, yourSide = side, pointsAwarded = 0, replayed = true)
            }
            val viewModel = viewModel()
            viewModel.nextQuestion()
            testScheduler.advanceUntilIdle()

            viewModel.vote(Side.A)
            testScheduler.advanceUntilIdle()

            assertEquals(LogResult.Ok("+0 total=42 replayed"), viewModel.log.first().result)
            assertEquals(
                true,
                viewModel.state.value.lastOutcome
                    ?.replayed,
            )
        }

    @Test
    fun `every vote is an answer with an attempt of its own`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.nextQuestion()
            testScheduler.advanceUntilIdle()

            viewModel.vote(Side.A)
            testScheduler.advanceUntilIdle()
            viewModel.vote(Side.A)
            testScheduler.advanceUntilIdle()
            viewModel.voteById("q1", Side.A)
            testScheduler.advanceUntilIdle()

            assertEquals(3, votes.attempts.toSet().size)
        }

    @Test
    fun `a vote by id on an unknown question surfaces QUESTION_NOT_FOUND as an Err entry`() =
        runTest(dispatcher) {
            votes.answer = { _, _ -> throw WyrException(DomainError.QUESTION_NOT_FOUND, "no such question") }
            val viewModel = viewModel()

            viewModel.voteById(" missing ", Side.A)
            testScheduler.advanceUntilIdle()

            val entry = viewModel.log.single()
            assertEquals("questionId=missing side=A attempt=${votes.attempts.single().value}", entry.args)
            assertEquals(LogResult.Err(DomainError.QUESTION_NOT_FOUND, "no such question"), entry.result)
        }

    @Test
    fun `Retry last vote sends the same vote again as the same attempt`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.nextQuestion()
            testScheduler.advanceUntilIdle()
            viewModel.vote(Side.B)
            testScheduler.advanceUntilIdle()

            viewModel.retryLastVote()
            testScheduler.advanceUntilIdle()

            val attempt = votes.attempts.first()
            assertEquals(listOf(attempt, attempt), votes.attempts)
            assertEquals(listOf("q1" to Side.B, "q1" to Side.B), votes.sent)
            assertEquals(
                listOf("retryLastVote", "vote").map { it to "questionId=q1 side=B attempt=${attempt.value}" },
                viewModel.log.take(2).map { it.action to it.args },
            )
        }

    @Test
    fun `a vote whose answer was lost can still be retried as the same attempt`() =
        runTest(dispatcher) {
            var lose = true
            votes.answer = { questionId, side ->
                if (lose) {
                    lose = false
                    throw WyrException(DomainError.NETWORK, "read timed out")
                }
                OUTCOME.copy(questionId = questionId, yourSide = side)
            }
            val viewModel = viewModel()
            viewModel.voteById("q7", Side.A)
            testScheduler.advanceUntilIdle()

            viewModel.retryLastVote()
            testScheduler.advanceUntilIdle()

            assertEquals(1, votes.attempts.toSet().size)
            assertEquals(2, votes.attempts.size)
            assertEquals(LogResult.Ok("+1 total=42"), viewModel.log.first().result)
        }

    @Test
    fun `Retry last vote with no vote sent does nothing`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            testScheduler.advanceUntilIdle()

            viewModel.retryLastVote()
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList(), votes.attempts)
            assertEquals(emptyList(), viewModel.log)
        }

    @Test
    fun `New guest forgets the last vote`() =
        runTest(dispatcher) {
            // Retrying it would be the new player's answer, not a retry of anything they did.
            val viewModel = viewModel()
            viewModel.voteById("q1", Side.A)
            testScheduler.advanceUntilIdle()

            viewModel.newGuest()
            testScheduler.advanceUntilIdle()
            viewModel.retryLastVote()
            testScheduler.advanceUntilIdle()

            assertEquals(null, viewModel.state.value.lastVote)
            assertEquals(1, votes.attempts.size)
        }

    @Test
    fun `Answer N answers N questions in a row with alternating sides`() =
        runTest(dispatcher) {
            val served =
                ArrayDeque(listOf(QUESTION, QUESTION.copy(id = "q2"), QUESTION.copy(id = "q3", answeredBefore = true)))
            questions.next = { served.removeFirst() }
            val viewModel = viewModel()

            viewModel.answerMany(3)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("q1" to Side.A, "q2" to Side.B, "q3" to Side.A), votes.sent)
            assertEquals(3, votes.attempts.toSet().size)
            val (a1, a2, a3) = votes.attempts.map { it.value }
            assertEquals(
                listOf(
                    Triple("answerMany", "n=3", LogResult.Ok("answered=3 looped=1 total=42")),
                    Triple(
                        "answer",
                        "3/3 questionId=q3 side=A attempt=$a3",
                        LogResult.Ok("question=q3 looped +1 total=42"),
                    ),
                    Triple("answer", "2/3 questionId=q2 side=B attempt=$a2", LogResult.Ok("question=q2 +1 total=42")),
                    Triple("answer", "1/3 questionId=q1 side=A attempt=$a1", LogResult.Ok("question=q1 +1 total=42")),
                ),
                viewModel.log.map { Triple(it.action, it.args, it.result) },
            )
            assertFalse(viewModel.state.value.isBusy)
        }

    @Test
    fun `Answer N stops at the first failure and leaves it to retry`() =
        runTest(dispatcher) {
            var answered = 0
            votes.answer = { questionId, side ->
                if (answered++ == 1) throw WyrException(DomainError.NETWORK, "read timed out")
                OUTCOME.copy(questionId = questionId, yourSide = side)
            }
            val viewModel = viewModel()

            viewModel.answerMany(3)
            testScheduler.advanceUntilIdle()

            assertEquals(2, votes.sent.size)
            assertEquals(
                listOf(
                    "answerMany" to LogResult.Err(DomainError.NETWORK, "read timed out"),
                    "answer" to LogResult.Ok("question=q1 +1 total=42"),
                ),
                viewModel.log.map { it.action to it.result },
            )
            // The lost one, so Retry last vote can send it again as the same attempt.
            assertEquals(SentVote("q1", Side.B, votes.attempts.last()), viewModel.state.value.lastVote)
        }

    @Test
    fun `Answer N out of range is logged as a crash and sends nothing`() =
        runTest(dispatcher) {
            // The screen only offers 1 to MAX_ANSWER_MANY, so anything else reaching here is a bug.
            val viewModel = viewModel()

            viewModel.answerMany(0)
            testScheduler.advanceUntilIdle()

            assertEquals("IllegalArgumentException", assertIs<LogResult.Crash>(viewModel.onlyResult()).type)
            assertEquals(emptyList(), calls)
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
            getNextQuestion = GetNextQuestion(questions, sessions),
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

        /** Every attempt cast, in order. */
        val attempts = mutableListOf<AttemptId>()

        /** The question and side of every vote cast, in order. */
        val sent = mutableListOf<Pair<String, Side>>()

        override suspend fun cast(
            questionId: String,
            side: Side,
            attempt: AttemptId,
        ): VoteOutcome {
            calls += "cast"
            attempts += attempt
            sent += questionId to side
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
