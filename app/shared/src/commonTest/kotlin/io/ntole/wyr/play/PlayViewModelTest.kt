package io.ntole.wyr.play

import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.question.GetNextQuestion
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.domain.session.SessionRepository
import io.ntole.wyr.core.domain.vote.AttemptId
import io.ntole.wyr.core.domain.vote.CastVote
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.core.domain.vote.VoteOutcome
import io.ntole.wyr.core.domain.vote.VoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlayViewModelTest {
    private val dispatcher = StandardTestDispatcher()

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
    fun `loads a question on creation`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            testScheduler.advanceUntilIdle()

            val state = assertIs<PlayUiState.Asking>(viewModel.state.value)
            assertEquals(QUESTION.id, state.question.id)
        }

    @Test
    fun `voting moves to the revealed state with the server's numbers`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            testScheduler.advanceUntilIdle()

            viewModel.choose(Side.A)
            testScheduler.advanceUntilIdle()

            val state = assertIs<PlayUiState.Revealed>(viewModel.state.value)
            assertEquals(Side.A, state.outcome.yourSide)
            assertEquals(42, state.outcome.totalPoints)
        }

    @Test
    fun `a second tap while submitting does not cast a second vote`() =
        runTest(dispatcher) {
            val votes = RecordingVoteRepository()
            val viewModel = viewModel(votes = votes)
            testScheduler.advanceUntilIdle()

            // Both calls land before the first one's coroutine gets to run.
            viewModel.choose(Side.A)
            viewModel.choose(Side.B)
            testScheduler.advanceUntilIdle()

            assertEquals(1, votes.callCount, "double tap must not double vote")
        }

    @Test
    fun `every tap is an answer with an attempt of its own`() =
        runTest(dispatcher) {
            // The same question twice, as when the feed loops back to it: the second tap answers it
            // again, and reusing the first attempt would have the server replay it instead.
            val votes = RecordingVoteRepository()
            val viewModel = viewModel(votes = votes)
            testScheduler.advanceUntilIdle()

            viewModel.choose(Side.A)
            testScheduler.advanceUntilIdle()
            viewModel.next()
            testScheduler.advanceUntilIdle()
            viewModel.choose(Side.A)
            testScheduler.advanceUntilIdle()

            assertEquals(2, votes.attempts.toSet().size)
        }

    @Test
    fun `retrying a vote lost to the network sends it again as the same attempt`() =
        runTest(dispatcher) {
            // If the first one landed and only its response was lost, the server replays it.
            val votes = RecordingVoteRepository(DomainError.NETWORK)
            val viewModel = viewModel(votes = votes)
            testScheduler.advanceUntilIdle()
            viewModel.choose(Side.B)
            testScheduler.advanceUntilIdle()
            assertIs<PlayUiState.Failed>(viewModel.state.value)

            viewModel.retry()
            testScheduler.advanceUntilIdle()

            assertIs<PlayUiState.Revealed>(viewModel.state.value)
            assertEquals(listOf(Side.B, Side.B), votes.sides)
            assertEquals(1, votes.attempts.toSet().size)
        }

    @Test
    fun `a second tap on Try again does not also move on`() =
        runTest(dispatcher) {
            val votes = RecordingVoteRepository(DomainError.NETWORK)
            val viewModel = viewModel(votes = votes)
            testScheduler.advanceUntilIdle()
            viewModel.choose(Side.A)
            testScheduler.advanceUntilIdle()

            viewModel.retry()
            viewModel.retry()
            testScheduler.advanceUntilIdle()

            assertIs<PlayUiState.Revealed>(viewModel.state.value)
            assertEquals(2, votes.callCount)
        }

    @Test
    fun `a vote the server refused is not sent again and Try again moves on`() =
        runTest(dispatcher) {
            // Sending it again would fail the same way, stranding the player on the error.
            val votes = RecordingVoteRepository(DomainError.QUESTION_NOT_FOUND)
            val viewModel = viewModel(votes = votes)
            testScheduler.advanceUntilIdle()
            viewModel.choose(Side.A)
            testScheduler.advanceUntilIdle()

            viewModel.retry()
            testScheduler.advanceUntilIdle()

            assertEquals(PlayUiState.Asking(QUESTION), viewModel.state.value)
            assertEquals(1, votes.callCount)
        }

    @Test
    fun `a network failure surfaces as a retryable failed state`() =
        runTest(dispatcher) {
            val viewModel =
                viewModel(
                    questions = FailingQuestionRepository(DomainError.NETWORK),
                )

            testScheduler.advanceUntilIdle()

            val state = assertIs<PlayUiState.Failed>(viewModel.state.value)
            assertEquals(DomainError.NETWORK, state.error)
        }

    @Test
    fun `an already-voted question is skipped rather than shown as an error`() =
        runTest(dispatcher) {
            // The question is spent and the player can do nothing about it, so stranding them on
            // an error screen would be a dead end.
            val viewModel = viewModel(votes = FailingVoteRepository(DomainError.ALREADY_VOTED))
            testScheduler.advanceUntilIdle()

            viewModel.choose(Side.A)
            testScheduler.advanceUntilIdle()

            assertTrue(
                viewModel.state.value is PlayUiState.Asking,
                "expected to advance to the next question, was ${viewModel.state.value}",
            )
        }

    private fun viewModel(
        questions: QuestionRepository = FakeQuestionRepository(),
        votes: VoteRepository = FakeVoteRepository(),
    ) = PlayViewModel(
        getNextQuestion = GetNextQuestion(questions, NoOpSessionRepository),
        castVote = CastVote(votes, NoOpSessionRepository),
        questions = questions,
    )

    private companion object {
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
                pointsAwarded = 17,
                totalPoints = 42,
            )
    }

    private class FakeQuestionRepository : QuestionRepository {
        override val category: StateFlow<Category?> = MutableStateFlow(null)

        override suspend fun next(): Question = QUESTION

        override suspend fun prefetch() = Unit

        override suspend fun setCategory(category: Category?) = Unit

        override suspend fun skip(questionId: String) = Unit

        override suspend fun reset() = Unit
    }

    private class FailingQuestionRepository(
        private val error: DomainError,
    ) : QuestionRepository {
        override val category: StateFlow<Category?> = MutableStateFlow(null)

        override suspend fun next(): Question = throw WyrException(error)

        override suspend fun prefetch() = Unit

        override suspend fun setCategory(category: Category?) = Unit

        override suspend fun skip(questionId: String) = Unit

        override suspend fun reset() = Unit
    }

    private class FakeVoteRepository : VoteRepository {
        override suspend fun cast(
            questionId: String,
            side: Side,
            attempt: AttemptId,
        ): VoteOutcome = OUTCOME.copy(yourSide = side)
    }

    /** Records every vote, and refuses the first ones with [failures], in order. */
    private class RecordingVoteRepository(
        vararg failures: DomainError,
    ) : VoteRepository {
        private val failures = failures.toMutableList()

        val attempts = mutableListOf<AttemptId>()

        val sides = mutableListOf<Side>()

        val callCount: Int get() = attempts.size

        override suspend fun cast(
            questionId: String,
            side: Side,
            attempt: AttemptId,
        ): VoteOutcome {
            attempts += attempt
            sides += side
            failures.removeFirstOrNull()?.let { throw WyrException(it) }
            return OUTCOME.copy(yourSide = side)
        }
    }

    private class FailingVoteRepository(
        private val error: DomainError,
    ) : VoteRepository {
        override suspend fun cast(
            questionId: String,
            side: Side,
            attempt: AttemptId,
        ): VoteOutcome = throw WyrException(error)
    }

    private object NoOpSessionRepository : SessionRepository {
        override suspend fun ensure(): String = "player-1"

        override suspend fun currentPlayerId(): String = "player-1"

        override suspend fun clear() = Unit
    }
}
