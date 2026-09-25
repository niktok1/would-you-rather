package io.ntole.wyr.play

import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.like.LikeRepository
import io.ntole.wyr.core.domain.like.QuestionLikes
import io.ntole.wyr.core.domain.like.SetLike
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.question.GetNextQuestion
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.domain.question.SkipQuestion
import io.ntole.wyr.core.domain.session.SessionRepository
import io.ntole.wyr.core.domain.vote.AttemptId
import io.ntole.wyr.core.domain.vote.CastVote
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.core.domain.vote.VoteOutcome
import io.ntole.wyr.core.domain.vote.VoteRepository
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertNull
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

    @Test
    fun `Skip records the skip then shows the next question and casts no vote`() =
        runTest(dispatcher) {
            val questions = FakeQuestionRepository(QUESTION, NEXT_QUESTION)
            val votes = RecordingVoteRepository()
            val viewModel = viewModel(questions = questions, votes = votes)
            testScheduler.advanceUntilIdle()

            viewModel.skip()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf(QUESTION.id), questions.skipped)
            // Asked, not revealed: a skip has no outcome, so no points and no tally to show.
            assertEquals(PlayUiState.Asking(NEXT_QUESTION), viewModel.state.value)
            assertEquals(0, votes.callCount, "a skip is no answer")
        }

    @Test
    fun `a skip the server did not record moves on all the same`() =
        runTest(dispatcher) {
            val questions = FakeQuestionRepository(QUESTION, NEXT_QUESTION, skipFailure = DomainError.NETWORK)
            val viewModel = viewModel(questions = questions)
            testScheduler.advanceUntilIdle()

            viewModel.skip()
            testScheduler.advanceUntilIdle()

            // Not kept on the question the player asked not to answer, nor on an error about it.
            assertEquals(PlayUiState.Asking(NEXT_QUESTION), viewModel.state.value)
            assertEquals(listOf(QUESTION.id), questions.skipped, "and not sent again")
        }

    @Test
    fun `a second tap on Skip sends one skip`() =
        runTest(dispatcher) {
            val questions = FakeQuestionRepository(QUESTION, NEXT_QUESTION, QUESTION)
            val viewModel = viewModel(questions = questions)
            testScheduler.advanceUntilIdle()

            // Both land before the first one's coroutine gets to run.
            viewModel.skip()
            viewModel.skip()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf(QUESTION.id), questions.skipped)
            assertEquals(PlayUiState.Asking(NEXT_QUESTION), viewModel.state.value, "one question skipped past")
        }

    @Test
    fun `Skip while the vote is in flight does nothing`() =
        runTest(dispatcher) {
            val questions = FakeQuestionRepository(QUESTION, NEXT_QUESTION)
            val viewModel = viewModel(questions = questions)
            testScheduler.advanceUntilIdle()

            viewModel.choose(Side.A)
            viewModel.skip()
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList(), questions.skipped)
            val state = assertIs<PlayUiState.Revealed>(viewModel.state.value)
            assertEquals(QUESTION, state.question)
        }

    @Test
    fun `Skip after answering does nothing`() =
        runTest(dispatcher) {
            // The question is answered, and Next question is the way on.
            val questions = FakeQuestionRepository(QUESTION, NEXT_QUESTION)
            val viewModel = viewModel(questions = questions)
            testScheduler.advanceUntilIdle()
            viewModel.choose(Side.A)
            testScheduler.advanceUntilIdle()

            viewModel.skip()
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList(), questions.skipped)
            assertIs<PlayUiState.Revealed>(viewModel.state.value)
        }

    @Test
    fun `the like count shows before answering`() =
        runTest(dispatcher) {
            val served = QUESTION.copy(likeCount = 3, likedByMe = true)
            val viewModel = viewModel(questions = FakeQuestionRepository(served))

            testScheduler.advanceUntilIdle()

            // As the feed counted them, with nothing answered yet.
            val state = assertIs<PlayUiState.Asking>(viewModel.state.value)
            assertEquals(3, state.question.likeCount)
            assertTrue(state.question.likedByMe)
        }

    @Test
    fun `Like likes the question being asked then shows the server's count`() =
        runTest(dispatcher) {
            val likes = FakeLikeRepository()
            // Others like it too, so the count shown is the server's and not one more than before.
            likes.answer = { questionId, liked -> QuestionLikes(questionId, likeCount = 7, likedByMe = liked) }
            val votes = RecordingVoteRepository()
            val viewModel = viewModel(FakeQuestionRepository(QUESTION.copy(likeCount = 3)), votes, likes)
            testScheduler.advanceUntilIdle()

            viewModel.toggleLike()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf(QUESTION.id to true), likes.sent)
            assertEquals(PlayUiState.Asking(QUESTION.copy(likeCount = 7, likedByMe = true)), viewModel.state.value)
            assertEquals(0, votes.callCount, "a like is no answer")
        }

    @Test
    fun `a like then an unlike each put the server's answer on the question`() =
        runTest(dispatcher) {
            val likes = FakeLikeRepository()
            val viewModel = viewModel(likes = likes)
            testScheduler.advanceUntilIdle()

            viewModel.toggleLike()
            testScheduler.advanceUntilIdle()
            assertEquals(PlayUiState.Asking(QUESTION.copy(likeCount = 1, likedByMe = true)), viewModel.state.value)

            viewModel.toggleLike()
            testScheduler.advanceUntilIdle()

            // The opposite of what the question on screen showed, each time.
            assertEquals(listOf(QUESTION.id to true, QUESTION.id to false), likes.sent)
            assertEquals(PlayUiState.Asking(QUESTION), viewModel.state.value)
        }

    @Test
    fun `a failed like leaves the question as it was and shows the error`() =
        runTest(dispatcher) {
            val likes = FakeLikeRepository()
            var lost = false
            likes.answer = { questionId, liked ->
                if (!lost) {
                    lost = true
                    throw WyrException(DomainError.NETWORK)
                }
                QuestionLikes(questionId, likeCount = 1, likedByMe = liked)
            }
            val viewModel = viewModel(likes = likes)
            testScheduler.advanceUntilIdle()

            viewModel.toggleLike()
            testScheduler.advanceUntilIdle()
            assertEquals(PlayUiState.Asking(QUESTION, likeError = DomainError.NETWORK), viewModel.state.value)

            viewModel.toggleLike()
            testScheduler.advanceUntilIdle()

            // A like both times: the first may have landed, and the server holds it once.
            assertEquals(listOf(QUESTION.id to true, QUESTION.id to true), likes.sent)
            assertEquals(PlayUiState.Asking(QUESTION.copy(likeCount = 1, likedByMe = true)), viewModel.state.value)
        }

    @Test
    fun `likes answered for another question are not put on the one on screen`() =
        runTest(dispatcher) {
            val likes = FakeLikeRepository()
            likes.answer = { _, liked -> QuestionLikes("q9", likeCount = 5, likedByMe = liked) }
            val viewModel = viewModel(likes = likes)
            testScheduler.advanceUntilIdle()

            viewModel.toggleLike()
            testScheduler.advanceUntilIdle()

            assertEquals(PlayUiState.Asking(QUESTION), viewModel.state.value)
        }

    @Test
    fun `Like works after answering and keeps the reveal`() =
        runTest(dispatcher) {
            val likes = FakeLikeRepository()
            val viewModel = viewModel(likes = likes)
            testScheduler.advanceUntilIdle()
            viewModel.choose(Side.B)
            testScheduler.advanceUntilIdle()

            viewModel.toggleLike()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf(QUESTION.id to true), likes.sent)
            assertEquals(
                PlayUiState.Revealed(QUESTION.copy(likeCount = 1, likedByMe = true), OUTCOME.copy(yourSide = Side.B)),
                viewModel.state.value,
            )
        }

    @Test
    fun `nothing else goes while a like is in flight`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val likes = FakeLikeRepository()
            likes.answer = { questionId, liked ->
                gate.await()
                QuestionLikes(questionId, likeCount = 1, likedByMe = liked)
            }
            val questions = FakeQuestionRepository(QUESTION, NEXT_QUESTION)
            val votes = RecordingVoteRepository()
            val viewModel = viewModel(questions, votes, likes)
            testScheduler.advanceUntilIdle()

            viewModel.toggleLike()
            testScheduler.advanceUntilIdle()
            viewModel.choose(Side.A)
            viewModel.skip()
            viewModel.toggleLike()
            testScheduler.advanceUntilIdle()

            assertEquals(0, votes.callCount)
            assertEquals(emptyList(), questions.skipped)
            assertEquals(1, likes.sent.size)
            gate.complete(Unit)
            testScheduler.advanceUntilIdle()
            assertEquals(PlayUiState.Asking(QUESTION.copy(likeCount = 1, likedByMe = true)), viewModel.state.value)
        }

    @Test
    fun `Like while the vote is in flight does nothing`() =
        runTest(dispatcher) {
            val likes = FakeLikeRepository()
            val viewModel = viewModel(likes = likes)
            testScheduler.advanceUntilIdle()

            viewModel.choose(Side.A)
            viewModel.toggleLike()
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList(), likes.sent)
            assertEquals(PlayUiState.Revealed(QUESTION, OUTCOME), viewModel.state.value)
        }

    /** One action at a time: the reveal waits for its like, which then lands on it. */
    @Test
    fun `Next while a like is in flight does nothing`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val likes = FakeLikeRepository()
            likes.answer = { questionId, liked ->
                gate.await()
                QuestionLikes(questionId, likeCount = 1, likedByMe = liked)
            }
            val questions = FakeQuestionRepository(QUESTION, NEXT_QUESTION)
            val viewModel = viewModel(questions, likes = likes)
            testScheduler.advanceUntilIdle()
            viewModel.choose(Side.A)
            testScheduler.advanceUntilIdle()
            viewModel.toggleLike()
            testScheduler.advanceUntilIdle()

            viewModel.next()
            testScheduler.advanceUntilIdle()
            gate.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertEquals(
                PlayUiState.Revealed(QUESTION.copy(likeCount = 1, likedByMe = true), OUTCOME),
                viewModel.state.value,
            )
            assertEquals(listOf("next"), questions.calls)
        }

    @Test
    fun `Next after the reveal shows the next question`() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeQuestionRepository(QUESTION, NEXT_QUESTION))
            testScheduler.advanceUntilIdle()
            viewModel.choose(Side.B)
            testScheduler.advanceUntilIdle()

            viewModel.next()
            testScheduler.advanceUntilIdle()

            assertEquals(PlayUiState.Asking(NEXT_QUESTION), viewModel.state.value)
        }

    /** Before the reveal a card is an answer and Skip the way past it: never the next question. */
    @Test
    fun `Next before answering does nothing`() =
        runTest(dispatcher) {
            val questions = FakeQuestionRepository(QUESTION, NEXT_QUESTION)
            val viewModel = viewModel(questions)
            testScheduler.advanceUntilIdle()

            viewModel.next()
            testScheduler.advanceUntilIdle()

            assertEquals(PlayUiState.Asking(QUESTION), viewModel.state.value)
            assertEquals(listOf("next"), questions.calls)
        }

    @Test
    fun `Next while the vote is in flight does nothing`() =
        runTest(dispatcher) {
            val questions = FakeQuestionRepository(QUESTION, NEXT_QUESTION)
            val viewModel = viewModel(questions)
            testScheduler.advanceUntilIdle()

            viewModel.choose(Side.A)
            viewModel.next()
            testScheduler.advanceUntilIdle()

            assertEquals(PlayUiState.Revealed(QUESTION, OUTCOME), viewModel.state.value)
            assertEquals(listOf("next"), questions.calls)
        }

    /** Both taps land before the first one's fetch runs: one question further, not two. */
    @Test
    fun `a second tap on Next loads one question`() =
        runTest(dispatcher) {
            val questions = FakeQuestionRepository(QUESTION, NEXT_QUESTION, FOOD_QUESTION)
            val viewModel = viewModel(questions)
            testScheduler.advanceUntilIdle()
            viewModel.choose(Side.A)
            testScheduler.advanceUntilIdle()

            viewModel.next()
            viewModel.next()
            testScheduler.advanceUntilIdle()

            assertEquals(PlayUiState.Asking(NEXT_QUESTION), viewModel.state.value)
            assertEquals(listOf("next", "next"), questions.calls)
        }

    @Test
    fun `the categories shown are the ones the repository plays`() =
        runTest(dispatcher) {
            // The repository outlives the screen, so it can open on a feed already filtered.
            val questions = FakeQuestionRepository()
            questions.categories.value = setOf(Category.ETHICS)
            val viewModel = viewModel(questions)

            assertEquals(setOf(Category.ETHICS), viewModel.categories.value)
        }

    @Test
    fun `the picker opens on the categories played now`() =
        runTest(dispatcher) {
            val questions = FakeQuestionRepository()
            questions.categories.value = setOf(Category.ETHICS)
            val viewModel = viewModel(questions)
            testScheduler.advanceUntilIdle()

            viewModel.openCategories()

            assertEquals(setOf(Category.ETHICS), viewModel.picking.value)
        }

    @Test
    fun `ticking categories in the picker plays nothing until they are applied`() =
        runTest(dispatcher) {
            val questions = FakeQuestionRepository()
            val viewModel = viewModel(questions)
            testScheduler.advanceUntilIdle()
            viewModel.openCategories()

            viewModel.toggleCategory(Category.FOOD)
            viewModel.toggleCategory(Category.ETHICS)
            testScheduler.advanceUntilIdle()

            assertEquals(setOf(Category.FOOD, Category.ETHICS), viewModel.picking.value)
            assertEquals(listOf("next"), questions.calls, "no change and no fetch yet")
            assertEquals(PlayUiState.Asking(QUESTION), viewModel.state.value)
        }

    @Test
    fun `ticking a ticked category takes it out and leaves the rest`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            testScheduler.advanceUntilIdle()
            viewModel.openCategories()
            viewModel.toggleCategory(Category.FOOD)
            viewModel.toggleCategory(Category.ETHICS)

            viewModel.toggleCategory(Category.FOOD)

            assertEquals(setOf(Category.ETHICS), viewModel.picking.value)
            viewModel.toggleCategory(Category.ETHICS)
            // None ticked is every category (CLAUDE.md §8d).
            assertEquals(emptySet(), viewModel.picking.value)
        }

    @Test
    fun `All in the picker unticks every category`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            testScheduler.advanceUntilIdle()
            viewModel.openCategories()
            viewModel.toggleCategory(Category.FOOD)
            viewModel.toggleCategory(Category.RANDOM)

            viewModel.selectAllCategories()

            assertEquals(emptySet(), viewModel.picking.value)
        }

    @Test
    fun `Other cannot be ticked`() =
        runTest(dispatcher) {
            // No feed can be filtered to it, and the repository refuses it.
            val viewModel = viewModel()
            testScheduler.advanceUntilIdle()
            viewModel.openCategories()

            viewModel.toggleCategory(Category.OTHER)

            assertEquals(emptySet(), viewModel.picking.value)
        }

    @Test
    fun `nothing is ticked or applied with the picker closed`() =
        runTest(dispatcher) {
            val questions = FakeQuestionRepository()
            val viewModel = viewModel(questions)
            testScheduler.advanceUntilIdle()

            viewModel.toggleCategory(Category.FOOD)
            viewModel.selectAllCategories()
            viewModel.applyCategories()
            testScheduler.advanceUntilIdle()

            assertNull(viewModel.picking.value)
            assertEquals(listOf("next"), questions.calls)
        }

    @Test
    fun `applying new categories sends them to the repository then shows a question from them`() =
        runTest(dispatcher) {
            val questions = FakeQuestionRepository(servedFor = mapOf(setOf(Category.FOOD) to FOOD_QUESTION))
            val viewModel = viewModel(questions)
            testScheduler.advanceUntilIdle()
            viewModel.openCategories()
            viewModel.toggleCategory(Category.FOOD)

            viewModel.applyCategories()
            testScheduler.advanceUntilIdle()

            // Changed first, so the question loaded is the new selection's.
            assertEquals(listOf("next", "setCategories [FOOD]", "next"), questions.calls)
            assertEquals(setOf(Category.FOOD), viewModel.categories.value)
            assertEquals(PlayUiState.Asking(FOOD_QUESTION), viewModel.state.value)
            assertNull(viewModel.picking.value, "the picker closes")
        }

    @Test
    fun `every category ticked is played as all of them and not as none`() =
        runTest(dispatcher) {
            // Not the same selection (CLAUDE.md §8d, *Categories*): a question filed only under
            // categories this build cannot name is in none of the five, and is served only to none.
            val questions = FakeQuestionRepository()
            val viewModel = viewModel(questions)
            testScheduler.advanceUntilIdle()
            viewModel.openCategories()

            Category.selectable.forEach(viewModel::toggleCategory)
            assertEquals(Category.selectable.toSet(), viewModel.picking.value, "ticked, not folded into All")
            viewModel.applyCategories()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("next", "setCategories ${Category.selectable}", "next"), questions.calls)
            assertEquals(Category.selectable.toSet(), viewModel.categories.value)
        }

    @Test
    fun `new categories from a vote lost to the network move on without sending it again`() =
        runTest(dispatcher) {
            // The player moved on instead of trying again: the vote counts only if it landed the
            // first time, and nothing can pay for it twice (CLAUDE.md §8d, *Retry safety*).
            val votes = RecordingVoteRepository(DomainError.NETWORK)
            val questions = FakeQuestionRepository(servedFor = mapOf(setOf(Category.FOOD) to FOOD_QUESTION))
            val viewModel = viewModel(questions, votes = votes)
            testScheduler.advanceUntilIdle()
            viewModel.choose(Side.A)
            testScheduler.advanceUntilIdle()
            val failed = assertIs<PlayUiState.Failed>(viewModel.state.value)
            assertEquals(QUESTION, failed.lostVote?.question, "a vote Try again would send again")

            viewModel.openCategories()
            viewModel.toggleCategory(Category.FOOD)
            viewModel.applyCategories()
            testScheduler.advanceUntilIdle()

            assertEquals(1, votes.callCount, "the lost vote is not sent again")
            assertEquals(PlayUiState.Asking(FOOD_QUESTION), viewModel.state.value)
        }

    @Test
    fun `new categories drop the answered question on screen too`() =
        runTest(dispatcher) {
            val questions = FakeQuestionRepository(servedFor = mapOf(setOf(Category.FOOD) to FOOD_QUESTION))
            val viewModel = viewModel(questions)
            testScheduler.advanceUntilIdle()
            viewModel.choose(Side.A)
            testScheduler.advanceUntilIdle()
            viewModel.openCategories()
            viewModel.toggleCategory(Category.FOOD)

            viewModel.applyCategories()
            testScheduler.advanceUntilIdle()

            assertEquals(PlayUiState.Asking(FOOD_QUESTION), viewModel.state.value)
        }

    @Test
    fun `applying the categories already played keeps the question on screen`() =
        runTest(dispatcher) {
            val questions = FakeQuestionRepository(QUESTION, NEXT_QUESTION)
            val viewModel = viewModel(questions)
            testScheduler.advanceUntilIdle()
            viewModel.openCategories()
            viewModel.toggleCategory(Category.FOOD)
            viewModel.toggleCategory(Category.FOOD)

            viewModel.applyCategories()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("next"), questions.calls, "no change and no fetch")
            assertEquals(PlayUiState.Asking(QUESTION), viewModel.state.value)
            assertNull(viewModel.picking.value, "the picker closes")
        }

    @Test
    fun `closing the picker plays nothing it ticked`() =
        runTest(dispatcher) {
            val questions = FakeQuestionRepository()
            val viewModel = viewModel(questions)
            testScheduler.advanceUntilIdle()
            viewModel.openCategories()
            viewModel.toggleCategory(Category.FOOD)

            viewModel.closeCategories()
            testScheduler.advanceUntilIdle()

            assertNull(viewModel.picking.value)
            assertEquals(emptySet(), viewModel.categories.value)
            assertEquals(listOf("next"), questions.calls)
        }

    @Test
    fun `a second tap on Play sends one change`() =
        runTest(dispatcher) {
            val questions = FakeQuestionRepository()
            val viewModel = viewModel(questions)
            testScheduler.advanceUntilIdle()
            viewModel.openCategories()
            viewModel.toggleCategory(Category.FOOD)

            // Both land before the first one's coroutine gets to run.
            viewModel.applyCategories()
            viewModel.applyCategories()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("next", "setCategories [FOOD]", "next"), questions.calls)
        }

    @Test
    fun `the picker does not open while a question loads`() =
        runTest(dispatcher) {
            // Loading from creation until the first question arrives.
            val viewModel = viewModel()

            viewModel.openCategories()
            testScheduler.advanceUntilIdle()

            assertNull(viewModel.picking.value)
        }

    @Test
    fun `the picker does not open while a vote is in flight`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            testScheduler.advanceUntilIdle()

            viewModel.choose(Side.A)
            viewModel.openCategories()
            testScheduler.advanceUntilIdle()

            assertNull(viewModel.picking.value)
            assertIs<PlayUiState.Revealed>(viewModel.state.value)
        }

    @Test
    fun `new categories wait while a like is in flight and the picker stays open`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val likes = FakeLikeRepository()
            likes.answer = { questionId, liked ->
                gate.await()
                QuestionLikes(questionId, likeCount = 1, likedByMe = liked)
            }
            val questions = FakeQuestionRepository(servedFor = mapOf(setOf(Category.FOOD) to FOOD_QUESTION))
            val viewModel = viewModel(questions, likes = likes)
            testScheduler.advanceUntilIdle()
            viewModel.openCategories()
            viewModel.toggleCategory(Category.FOOD)
            viewModel.toggleLike()
            testScheduler.advanceUntilIdle()

            viewModel.applyCategories()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("next"), questions.calls, "refused while the like is in flight")
            assertEquals(setOf(Category.FOOD), viewModel.picking.value, "and kept to apply later")
            gate.complete(Unit)
            testScheduler.advanceUntilIdle()
            viewModel.applyCategories()
            testScheduler.advanceUntilIdle()
            assertEquals(PlayUiState.Asking(FOOD_QUESTION), viewModel.state.value)
        }

    @Test
    fun `categories with nothing to serve show out of questions and can be changed from there`() =
        runTest(dispatcher) {
            // Whatever the feed answers is the server's rule: here, no questions at all.
            val questions = FakeQuestionRepository(servedFor = mapOf(setOf(Category.RANDOM) to null))
            val viewModel = viewModel(questions)
            testScheduler.advanceUntilIdle()
            viewModel.openCategories()
            viewModel.toggleCategory(Category.RANDOM)
            viewModel.applyCategories()
            testScheduler.advanceUntilIdle()
            assertEquals(PlayUiState.Failed(DomainError.OUT_OF_QUESTIONS), viewModel.state.value)

            viewModel.openCategories()
            viewModel.selectAllCategories()
            viewModel.applyCategories()
            testScheduler.advanceUntilIdle()

            assertEquals(emptySet(), viewModel.categories.value)
            assertEquals(PlayUiState.Asking(QUESTION), viewModel.state.value)
        }

    private fun viewModel(
        questions: QuestionRepository = FakeQuestionRepository(),
        votes: VoteRepository = FakeVoteRepository(),
        likes: LikeRepository = FakeLikeRepository(),
    ) = PlayViewModel(
        getNextQuestion = GetNextQuestion(questions, NoOpSessionRepository),
        castVote = CastVote(votes, NoOpSessionRepository),
        skipQuestion = SkipQuestion(questions, NoOpSessionRepository),
        setLike = SetLike(likes, NoOpSessionRepository),
        questions = questions,
    )

    private companion object {
        val QUESTION =
            Question(
                id = "q1",
                optionA = "Fly",
                optionB = "Turn invisible",
                categories = setOf(Category.SUPERPOWERS),
            )

        val NEXT_QUESTION =
            Question(
                id = "q2",
                optionA = "Always be cold",
                optionB = "Always be hot",
                categories = setOf(Category.LIFESTYLE),
            )

        val FOOD_QUESTION =
            Question(
                id = "f1",
                optionA = "Only eat soup",
                optionB = "Never eat soup again",
                categories = setOf(Category.FOOD),
            )

        val OUTCOME =
            VoteOutcome(
                yourSide = Side.A,
                tally = Tally(votesA = 7, votesB = 3),
                pointsAwarded = 17,
                totalPoints = 42,
            )
    }

    /**
     * Serves [served] in order, then the last of them again and again, or, while the categories
     * selected are a key of [servedFor], that key's question every time, and none is out of
     * questions. Records every skip, and refuses each with [skipFailure] when there is one.
     */
    private class FakeQuestionRepository(
        vararg served: Question,
        private val skipFailure: DomainError? = null,
        private val servedFor: Map<Set<Category>, Question?> = emptyMap(),
    ) : QuestionRepository {
        private val served = served.toMutableList().ifEmpty { mutableListOf(QUESTION) }

        val skipped = mutableListOf<String>()

        /** Every fetch and every change of categories, in order, the categories in declaration order. */
        val calls = mutableListOf<String>()

        override val categories = MutableStateFlow<Set<Category>>(emptySet())

        override suspend fun next(): Question {
            calls += "next"
            val selected = categories.value
            if (selected in servedFor) {
                return servedFor[selected] ?: throw WyrException(DomainError.OUT_OF_QUESTIONS)
            }
            return if (served.size > 1) served.removeAt(0) else served.first()
        }

        override suspend fun prefetch() = Unit

        override suspend fun setCategories(categories: Set<Category>) {
            calls += "setCategories ${categories.sorted()}"
            this.categories.value = categories
        }

        override suspend fun skip(questionId: String) {
            skipped += questionId
            skipFailure?.let { throw WyrException(it) }
        }

        override suspend fun reset() = Unit
    }

    private class FailingQuestionRepository(
        private val error: DomainError,
    ) : QuestionRepository {
        override val categories: StateFlow<Set<Category>> = MutableStateFlow(emptySet())

        override suspend fun next(): Question = throw WyrException(error)

        override suspend fun prefetch() = Unit

        override suspend fun setCategories(categories: Set<Category>) = Unit

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

    /** Answers every like as the server would set it on a question nobody else likes, and records it. */
    private class FakeLikeRepository : LikeRepository {
        var answer: suspend (String, Boolean) -> QuestionLikes = { questionId, liked ->
            QuestionLikes(questionId, likeCount = if (liked) 1 else 0, likedByMe = liked)
        }

        /** The question and `liked` of every like sent, in order. */
        val sent = mutableListOf<Pair<String, Boolean>>()

        override suspend fun setLiked(
            questionId: String,
            liked: Boolean,
        ): QuestionLikes {
            sent += questionId to liked
            return answer(questionId, liked)
        }
    }

    private object NoOpSessionRepository : SessionRepository {
        override suspend fun ensure(): String = "player-1"
    }
}
