package io.ntole.wyr.dev

import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.player.GetPlayerStats
import io.ntole.wyr.core.domain.player.PlayerRepository
import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.question.GetNextQuestion
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.question.QuestionCache
import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.domain.question.SkipQuestion
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
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val players = FakePlayers(calls, sessions)

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
            val viewModel = openConsole()
            assertEquals(sessionOf("p1"), viewModel.state.value.session)
            sessions.playerId = "p2"

            viewModel.ensureSession()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(listOf(LogEntry("ensureSession", "", 0, LogResult.Ok("playerId=p2"))), state.log)
            assertEquals(sessionOf("p2"), state.session)
        }

    @Test
    fun `Reset queue shows the emptied queue in the header`() =
        runTest(dispatcher) {
            val viewModel = openConsole()
            assertEquals(QUEUE_SIZE, viewModel.state.value.queueSize)

            viewModel.resetQueue()
            testScheduler.advanceUntilIdle()

            assertEquals(0, viewModel.state.value.queueSize)
        }

    @Test
    fun `a classified failure is logged as Err with its message`() =
        runTest(dispatcher) {
            val viewModel = openConsole()
            sessions.ensure = { throw WyrException(DomainError.NETWORK, "connect timed out") }

            viewModel.ensureSession()
            testScheduler.advanceUntilIdle()

            assertEquals(LogResult.Err(DomainError.NETWORK, "connect timed out"), viewModel.onlyResult())
        }

    @Test
    fun `anything else thrown is logged as Crash`() =
        runTest(dispatcher) {
            val viewModel = openConsole()
            sessions.ensure = { error("boom") }

            viewModel.ensureSession()
            testScheduler.advanceUntilIdle()

            assertEquals(LogResult.Crash("IllegalStateException", "boom"), viewModel.onlyResult())
        }

    @Test
    fun `a header that cannot be read keeps the action entry and is logged on its own`() =
        runTest(dispatcher) {
            // What a browser with site data blocked does: localStorage throws on every read.
            diagnostics.info = { error("storage blocked") }
            val viewModel = openConsole()

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
            val viewModel = openConsole()
            sessions.ensure = { throw CancellationException("caller went away") }

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
            val viewModel = openConsole()

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
            val viewModel = openConsole()

            viewModel.newGuest()
            testScheduler.advanceUntilIdle()

            // The second ensure is GetNextQuestion making sure of the session it just got, and the
            // last two are the new player's stats being read.
            assertEquals(listOf("clear", "reset", "ensure", "ensure", "next", "ensure", "stats"), calls)
            assertEquals(QUESTION, viewModel.state.value.question)
            assertEquals(LogResult.Ok("playerId=p1 question=q1"), viewModel.onlyResult())
        }

    @Test
    fun `New guest shows the new player in the header`() =
        runTest(dispatcher) {
            val viewModel = openConsole()
            viewModel.ensureSession()
            testScheduler.advanceUntilIdle()
            sessions.playerId = "p2"

            viewModel.newGuest()
            testScheduler.advanceUntilIdle()

            assertEquals(sessionOf("p2"), viewModel.state.value.session)
        }

    @Test
    fun `toggling a category filters the feed to it then loads a question from it`() =
        runTest(dispatcher) {
            val viewModel = openConsole()
            questions.next = { QUESTION.copy(id = "f1", categories = setOf(Category.FOOD)) }

            viewModel.toggleCategory(Category.FOOD)
            testScheduler.advanceUntilIdle()

            // Switched first, so the question loaded is the new selection's.
            assertEquals(listOf("setCategories [FOOD]", "ensure", "next"), calls)
            val state = viewModel.state.value
            assertEquals(setOf(Category.FOOD), state.categories)
            assertEquals("f1", state.question?.id)
            val entry = LogEntry("selectCategories", "categories=FOOD", 0, LogResult.Ok("question=f1"))
            assertEquals(entry, state.log.single())
        }

    @Test
    fun `toggling a second category plays both in declaration order`() =
        runTest(dispatcher) {
            val viewModel = openConsole()
            viewModel.toggleCategory(Category.ETHICS)
            testScheduler.advanceUntilIdle()
            calls.clear()

            viewModel.toggleCategory(Category.FOOD)
            testScheduler.advanceUntilIdle()

            // Not in the order they were toggled: in the order the chips are.
            assertEquals(listOf("setCategories [FOOD, ETHICS]", "ensure", "next"), calls)
            val categories = viewModel.state.value.categories
            assertEquals(listOf(Category.FOOD, Category.ETHICS), categories.toList())
            assertEquals("categories=FOOD,ETHICS", viewModel.log.first().args)
        }

    @Test
    fun `toggling a selected category takes it out and leaves the rest`() =
        runTest(dispatcher) {
            val viewModel = openConsole()
            listOf(Category.FOOD, Category.ETHICS).forEach {
                viewModel.toggleCategory(it)
                testScheduler.advanceUntilIdle()
            }
            calls.clear()

            viewModel.toggleCategory(Category.FOOD)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("setCategories [ETHICS]", "ensure", "next"), calls)
            assertEquals(setOf(Category.ETHICS), viewModel.state.value.categories)
        }

    @Test
    fun `toggling the last selected category out lifts the filter`() =
        runTest(dispatcher) {
            // None picked is every category (CLAUDE.md §8d).
            val viewModel = openConsole()
            viewModel.toggleCategory(Category.FOOD)
            testScheduler.advanceUntilIdle()
            calls.clear()

            viewModel.toggleCategory(Category.FOOD)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("setCategories []", "ensure", "next"), calls)
            assertEquals(emptySet(), viewModel.state.value.categories)
            assertEquals("categories=all", viewModel.log.first().args)
        }

    @Test
    fun `All clears the selection`() =
        runTest(dispatcher) {
            val viewModel = openConsole()
            listOf(Category.FOOD, Category.RANDOM).forEach {
                viewModel.toggleCategory(it)
                testScheduler.advanceUntilIdle()
            }
            calls.clear()

            viewModel.selectAllCategories()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("setCategories []", "ensure", "next"), calls)
            assertEquals(emptySet(), viewModel.state.value.categories)
            assertEquals("categories=all", viewModel.log.first().args)
        }

    @Test
    fun `the Category row shows the selection the repository holds`() =
        runTest(dispatcher) {
            // The repository outlives the console, so it can open on a feed already filtered.
            questions.categories.value = setOf(Category.ETHICS)
            val viewModel = openConsole()
            assertEquals(setOf(Category.ETHICS), viewModel.state.value.categories)

            questions.categories.value = setOf(Category.FOOD, Category.RANDOM)
            testScheduler.advanceUntilIdle()

            assertEquals(setOf(Category.FOOD, Category.RANDOM), viewModel.state.value.categories)
        }

    @Test
    fun `a toggle adds to the selection the repository already holds`() =
        runTest(dispatcher) {
            questions.categories.value = setOf(Category.ETHICS)
            val viewModel = openConsole()

            viewModel.toggleCategory(Category.SUPERPOWERS)
            testScheduler.advanceUntilIdle()

            assertEquals("setCategories [ETHICS, SUPERPOWERS]", calls.first())
        }

    @Test
    fun `New guest keeps the categories selected`() =
        runTest(dispatcher) {
            val viewModel = openConsole()
            viewModel.toggleCategory(Category.FOOD)
            testScheduler.advanceUntilIdle()
            calls.clear()

            viewModel.newGuest()
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList(), calls.filter { it.startsWith("setCategories") })
            assertEquals(setOf(Category.FOOD), viewModel.state.value.categories)
        }

    @Test
    fun `a selection with nothing to serve is still selected and its failure logged`() =
        runTest(dispatcher) {
            val viewModel = openConsole()
            questions.next = { throw WyrException(DomainError.OUT_OF_QUESTIONS, "server returned no questions") }

            viewModel.toggleCategory(Category.RANDOM)
            testScheduler.advanceUntilIdle()

            assertEquals(setOf(Category.RANDOM), viewModel.state.value.categories)
            assertEquals(
                LogResult.Err(DomainError.OUT_OF_QUESTIONS, "server returned no questions"),
                viewModel.onlyResult(),
            )
            assertFalse(viewModel.state.value.isBusy)
        }

    @Test
    fun `Skip records the skip then loads the next question then reads the stats`() =
        runTest(dispatcher) {
            val viewModel = openConsole()
            viewModel.nextQuestion()
            testScheduler.advanceUntilIdle()
            questions.next = { QUESTION.copy(id = "q2") }
            calls.clear()

            viewModel.skip()
            testScheduler.advanceUntilIdle()

            // Each use case ensures the session first: the skip, the next question, then the stats.
            assertEquals(listOf("ensure", "skip q1", "ensure", "next", "ensure", "stats"), calls)
            assertEquals(QUESTION.copy(id = "q2"), viewModel.state.value.question)
            assertEquals(LogEntry("skip", "questionId=q1", 0, LogResult.Ok("question=q2")), viewModel.log.first())
            assertEquals(emptyList(), votes.sent, "a skip is no vote")
        }

    @Test
    fun `a skip the server did not record is logged and the next question loads anyway`() =
        runTest(dispatcher) {
            val viewModel = openConsole()
            viewModel.nextQuestion()
            testScheduler.advanceUntilIdle()
            questions.skip = { throw WyrException(DomainError.NETWORK, "read timed out") }
            questions.next = { QUESTION.copy(id = "q2") }

            viewModel.skip()
            testScheduler.advanceUntilIdle()

            assertEquals(QUESTION.copy(id = "q2"), viewModel.state.value.question, "the player is not kept on q1")
            assertEquals(
                listOf(
                    Triple("skip", "questionId=q1", LogResult.Ok("question=q2 skip=unrecorded")),
                    Triple("recordSkip", "questionId=q1", LogResult.Err(DomainError.NETWORK, "read timed out")),
                ),
                viewModel.log.take(2).map { Triple(it.action, it.args, it.result) },
            )
            assertFalse(viewModel.state.value.isBusy)
        }

    @Test
    fun `a cancelled skip is not taken for one that failed`() =
        runTest(dispatcher) {
            val viewModel = openConsole()
            viewModel.nextQuestion()
            testScheduler.advanceUntilIdle()
            questions.skip = { throw CancellationException("caller went away") }
            calls.clear()

            viewModel.skip()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("nextQuestion"), viewModel.log.map { it.action }, "neither the skip nor its send")
            assertEquals(listOf("ensure", "skip q1"), calls, "and no next question after it")
            assertFalse(viewModel.state.value.isBusy)
        }

    @Test
    fun `Skip with no question on screen does nothing`() =
        runTest(dispatcher) {
            val viewModel = openConsole()

            viewModel.skip()
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList(), calls)
            assertEquals(emptyList(), viewModel.log)
        }

    @Test
    fun `a vote shows the outcome the server returned`() =
        runTest(dispatcher) {
            val viewModel = openConsole()
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
            val viewModel = openConsole()
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
            val viewModel = openConsole()
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
            val viewModel = openConsole()
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
            val viewModel = openConsole()

            viewModel.voteById(" missing ", Side.A)
            testScheduler.advanceUntilIdle()

            val entry = viewModel.log.single()
            assertEquals("questionId=missing side=A attempt=${votes.attempts.single().value}", entry.args)
            assertEquals(LogResult.Err(DomainError.QUESTION_NOT_FOUND, "no such question"), entry.result)
        }

    @Test
    fun `Retry last vote sends the same vote again as the same attempt`() =
        runTest(dispatcher) {
            val viewModel = openConsole()
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
            val viewModel = openConsole()
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
            val viewModel = openConsole()

            viewModel.retryLastVote()
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList(), votes.attempts)
            assertEquals(emptyList(), viewModel.log)
        }

    @Test
    fun `New guest forgets the last vote`() =
        runTest(dispatcher) {
            // Retrying it would be the new player's answer, not a retry of anything they did.
            val viewModel = openConsole()
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
            val viewModel = openConsole()

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
            val viewModel = openConsole()

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
            val viewModel = openConsole()

            viewModel.answerMany(0)
            testScheduler.advanceUntilIdle()

            assertEquals("IllegalArgumentException", assertIs<LogResult.Crash>(viewModel.onlyResult()).type)
            // Only the stats read that follows every Answer N.
            assertEquals(listOf("ensure", "stats"), calls)
        }

    @Test
    fun `the log keeps only the newest entries`() =
        runTest(dispatcher) {
            val viewModel = openConsole()
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
    fun `opening the console reads the stats and so ensures a session`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(statsOf("p1"), state.stats)
            assertEquals(listOf("ensure", "stats"), calls)
            assertEquals(sessionOf("p1"), state.session, "the header shows the session the read ensured")
            assertEquals(emptyList(), state.log, "a read that worked is not logged")
            assertFalse(state.pointsMismatch, "no vote yet to disagree with")
            assertFalse(state.isBusy)
        }

    @Test
    fun `nothing else runs while the stats read on opening is in flight`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<PlayerStats>()
            players.stats = { answer.await() }
            val viewModel = viewModel()
            testScheduler.advanceUntilIdle()

            viewModel.nextQuestion()
            testScheduler.advanceUntilIdle()
            answer.complete(statsOf("p1"))
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("ensure", "stats"), calls)
            assertEquals(emptyList(), viewModel.log)
        }

    @Test
    fun `a stats read that fails on opening is logged and shows no stats`() =
        runTest(dispatcher) {
            players.stats = { throw WyrException(DomainError.NETWORK, "connect timed out") }
            val viewModel = viewModel()
            testScheduler.advanceUntilIdle()

            assertEquals(null, viewModel.state.value.stats)
            assertEquals(
                LogEntry("refreshStats", "", 0, LogResult.Err(DomainError.NETWORK, "connect timed out")),
                viewModel.log.single(),
            )
            assertFalse(viewModel.state.value.isBusy)
        }

    @Test
    fun `every kind of vote reads the stats again`() =
        runTest(dispatcher) {
            val viewModel = openConsole()
            viewModel.nextQuestion()
            testScheduler.advanceUntilIdle()
            val opened = players.reads

            viewModel.vote(Side.A)
            testScheduler.advanceUntilIdle()
            assertEquals(opened + 1, players.reads, "after a vote")
            viewModel.voteById("q2", Side.B)
            testScheduler.advanceUntilIdle()
            assertEquals(opened + 2, players.reads, "after a vote by id")
            viewModel.retryLastVote()
            testScheduler.advanceUntilIdle()
            assertEquals(opened + 3, players.reads, "after a retry")

            assertEquals(statsOf("p1"), viewModel.state.value.stats)
        }

    @Test
    fun `Answer N reads the stats once when the run is over`() =
        runTest(dispatcher) {
            val viewModel = openConsole()

            viewModel.answerMany(3)
            testScheduler.advanceUntilIdle()

            assertEquals(3, votes.sent.size)
            assertEquals(listOf("stats"), calls.filter { it == "stats" })
            assertEquals(statsOf("p1"), viewModel.state.value.stats)
        }

    @Test
    fun `New guest shows the new player's stats`() =
        runTest(dispatcher) {
            val viewModel = openConsole()
            sessions.playerId = "p2"

            viewModel.newGuest()
            testScheduler.advanceUntilIdle()

            assertEquals(statsOf("p2"), viewModel.state.value.stats)
        }

    @Test
    fun `New guest shows no stats rather than the old player's when the read fails`() =
        runTest(dispatcher) {
            val viewModel = openConsole()
            sessions.playerId = "p2"
            players.stats = { throw WyrException(DomainError.NETWORK, "read timed out") }

            viewModel.newGuest()
            testScheduler.advanceUntilIdle()

            assertEquals(null, viewModel.state.value.stats)
            assertEquals("refreshStats", viewModel.log.first().action)
        }

    @Test
    fun `stats that agree with the last outcome raise no flag`() =
        runTest(dispatcher) {
            val viewModel = openConsole()

            viewModel.voteById("q1", Side.A)
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(OUTCOME.totalPoints, state.stats?.totalPoints)
            assertFalse(state.pointsMismatch)
        }

    @Test
    fun `a vote that landed without its answer shows as a mismatch until it is replayed`() =
        runTest(dispatcher) {
            val viewModel = openConsole()
            viewModel.voteById("q1", Side.A)
            testScheduler.advanceUntilIdle()
            // The next vote is paid, but its answer never arrives.
            var landed = false
            votes.answer = { questionId, side ->
                if (!landed) {
                    landed = true
                    throw WyrException(DomainError.NETWORK, "read timed out")
                }
                OUTCOME.copy(questionId = questionId, yourSide = side, totalPoints = 43, replayed = true)
            }
            players.stats = { statsOf("p1").copy(totalPoints = 43) }

            viewModel.voteById("q2", Side.B)
            testScheduler.advanceUntilIdle()
            assertTrue(viewModel.state.value.pointsMismatch, "the server has a point the console saw no outcome for")

            viewModel.retryLastVote()
            testScheduler.advanceUntilIdle()
            assertFalse(viewModel.state.value.pointsMismatch, "the replay reports the total the stats do")
        }

    @Test
    fun `stats read as a fresh guest are not compared with the outcome paid to the player before`() =
        runTest(dispatcher) {
            val viewModel = openConsole()
            viewModel.voteById("q1", Side.A)
            testScheduler.advanceUntilIdle()
            // What a restarted dev server does to the next read: the session is refused and
            // recovered, and the stats that come back are a fresh guest's, with nothing paid yet.
            players.stats = {
                sessions.recover("p2")
                statsOf("p2").copy(totalPoints = 0)
            }

            viewModel.readStats()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("p2", state.stats?.playerId)
            assertTrue(state.statsForAnotherPlayer)
            assertFalse(state.pointsMismatch, "a fresh guest's total says nothing about p1's outcome")
        }

    @Test
    fun `a vote sent again as a fresh guest is compared with that guest's stats`() =
        runTest(dispatcher) {
            val viewModel = openConsole()
            votes.answer = { questionId, side ->
                sessions.recover("p2")
                OUTCOME.copy(questionId = questionId, yourSide = side)
            }

            viewModel.voteById("q1", Side.A)
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("p2", state.lastOutcomePlayerId, "the player it was paid to, not the one it was sent for")
            assertFalse(state.statsForAnotherPlayer)
        }

    @Test
    fun `a vote drops the stats it outdated when they cannot be read again`() =
        runTest(dispatcher) {
            val viewModel = openConsole()
            players.stats = { throw WyrException(DomainError.NETWORK, "read timed out") }

            // Its total is not the one the stats read on opening hold, but those are from before it.
            votes.answer = { questionId, side ->
                OUTCOME.copy(questionId = questionId, yourSide = side, totalPoints = 43)
            }
            viewModel.voteById("q1", Side.A)
            testScheduler.advanceUntilIdle()

            assertEquals(null, viewModel.state.value.stats)
            assertFalse(viewModel.state.value.pointsMismatch)
            assertEquals(listOf("refreshStats", "voteById"), viewModel.log.take(2).map { it.action })
        }

    @Test
    fun `a like of the player's own question since the outcome is not taken for a mismatch`() =
        runTest(dispatcher) {
            val viewModel = openConsole()
            viewModel.voteById("q1", Side.A)
            testScheduler.advanceUntilIdle()
            // One of the player's questions liked since: a point more, paid without a vote.
            players.stats = { statsOf("p1").copy(totalPoints = OUTCOME.totalPoints + 1, likesReceived = 1) }

            viewModel.readStats()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(0, state.likesReceivedAtOutcome)
            assertTrue(state.likesMovedSinceOutcome)
            assertFalse(state.pointsMismatch, "the like paid the point, not a vote the console has no outcome for")
        }

    @Test
    fun `the stats read right after a vote are compared with it whatever likes they count`() =
        runTest(dispatcher) {
            // The total already holds the likes' points, as the outcome's does.
            players.stats = { statsOf("p1").copy(likesReceived = 5) }
            val viewModel = openConsole()

            viewModel.voteById("q1", Side.A)
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(5, state.likesReceivedAtOutcome)
            assertFalse(state.likesMovedSinceOutcome)
            assertFalse(state.pointsMismatch)
        }

    @Test
    fun `the next vote measures likes from the stats read after it`() =
        runTest(dispatcher) {
            val viewModel = openConsole()
            viewModel.voteById("q1", Side.A)
            testScheduler.advanceUntilIdle()
            players.stats = { statsOf("p1").copy(totalPoints = OUTCOME.totalPoints + 1, likesReceived = 1) }
            viewModel.readStats()
            testScheduler.advanceUntilIdle()
            assertTrue(viewModel.state.value.likesMovedSinceOutcome)

            // The next vote's total counts the like, and so does the read after it.
            votes.answer = { questionId, side ->
                OUTCOME.copy(questionId = questionId, yourSide = side, totalPoints = OUTCOME.totalPoints + 2)
            }
            players.stats = { statsOf("p1").copy(totalPoints = OUTCOME.totalPoints + 2, likesReceived = 1) }
            viewModel.voteById("q2", Side.B)
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(1, state.likesReceivedAtOutcome)
            assertFalse(state.likesMovedSinceOutcome)
            assertFalse(state.pointsMismatch)
        }

    @Test
    fun `Read stats reads them again as an action of its own`() =
        runTest(dispatcher) {
            val viewModel = openConsole()
            players.stats = { statsOf("p1").copy(cycle = 3, dueThisCycle = 24, likesReceived = 5) }

            viewModel.readStats()
            testScheduler.advanceUntilIdle()

            val stats = viewModel.state.value.stats
            assertEquals(3, stats?.cycle)
            assertEquals(
                LogResult.Ok("total=42 answers=42 questions=20 cycle=3 due=24 likes=5"),
                viewModel.onlyResult(),
            )
        }

    @Test
    fun `the time an action took is logged`() =
        runTest(dispatcher) {
            questions.next = {
                delay(250)
                QUESTION
            }
            val viewModel = openConsole()

            viewModel.nextQuestion()
            testScheduler.advanceUntilIdle()

            assertEquals(250, viewModel.log.single().elapsedMillis)
        }

    /**
     * The console once it has opened: the stats read on opening is done, and [calls] records only
     * what the test does from then on. [viewModel] is for the tests about opening itself.
     */
    private fun openConsole(): DevConsoleViewModel =
        viewModel().also {
            dispatcher.scheduler.advanceUntilIdle()
            calls.clear()
        }

    private fun viewModel() =
        DevConsoleViewModel(
            apiBaseUrl = "http://localhost:8080",
            sessions = sessions,
            diagnostics = diagnostics,
            questions = questions,
            queue = queue,
            getNextQuestion = GetNextQuestion(questions, sessions),
            skipQuestion = SkipQuestion(questions, sessions),
            castVote = CastVote(votes, sessions),
            getPlayerStats = GetPlayerStats(players, sessions),
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

        /** Agrees with [OUTCOME] on the total, as the server's stats do after that vote. */
        fun statsOf(playerId: String) =
            PlayerStats(
                playerId = playerId,
                totalPoints = OUTCOME.totalPoints,
                answersGiven = OUTCOME.totalPoints,
                questionsAnswered = 20,
                cycle = 2,
                dueThisCycle = 4,
                likesReceived = 0,
            )

        val QUESTION =
            Question(
                id = "q1",
                optionA = "Fly",
                optionB = "Turn invisible",
                categories = setOf(Category.SUPERPOWERS),
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

        /**
         * The player the last [ensure] minted, or [recover] put in its place, until [clear] drops
         * it. What the header and [currentPlayerId] report.
         */
        var stored: String? = null
            private set

        override suspend fun ensure(): String {
            calls += "ensure"
            return ensure.invoke().also { stored = it }
        }

        override suspend fun currentPlayerId(): String? = stored

        override suspend fun clear() {
            calls += "clear"
            stored = null
        }

        /**
         * What session recovery does to a session the server has stopped accepting: a fresh guest
         * in its place, stored as if [ensure] had minted it.
         */
        fun recover(playerId: String) {
            this.playerId = playerId
            stored = playerId
        }
    }

    private class FakeQuestions(
        private val calls: MutableList<String>,
        private val queue: FakeQueue,
    ) : QuestionRepository {
        var next: suspend () -> Question = { QUESTION }
        var skip: suspend (String) -> Unit = {}

        override val categories = MutableStateFlow<Set<Category>>(emptySet())

        override suspend fun next(): Question {
            calls += "next"
            return next.invoke()
        }

        override suspend fun prefetch() = Unit

        override suspend fun setCategories(categories: Set<Category>) {
            calls += "setCategories $categories"
            this.categories.value = categories
        }

        override suspend fun skip(questionId: String) {
            calls += "skip $questionId"
            skip.invoke(questionId)
        }

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

    /** Reads for whichever player [FakeSessions] holds, as the server answers for the bearer's. */
    private class FakePlayers(
        private val calls: MutableList<String>,
        private val sessions: FakeSessions,
    ) : PlayerRepository {
        var stats: suspend () -> PlayerStats = { statsOf(sessions.stored ?: "no session") }

        /** How many times the stats were read. */
        var reads = 0
            private set

        override suspend fun stats(): PlayerStats {
            calls += "stats"
            reads++
            return stats.invoke()
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
