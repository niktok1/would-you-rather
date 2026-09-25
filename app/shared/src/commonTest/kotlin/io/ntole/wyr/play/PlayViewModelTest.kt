package io.ntole.wyr.play

import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.category.CategoryRepository
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.like.LikeRepository
import io.ntole.wyr.core.domain.like.QuestionLikes
import io.ntole.wyr.core.domain.like.SetLike
import io.ntole.wyr.core.domain.player.GetPlayerStats
import io.ntole.wyr.core.domain.player.PlayerRepository
import io.ntole.wyr.core.domain.player.PlayerStats
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

    /**
     * The second tap of a double tap on a card can land after a quick answer. Until a moment after the
     * reveal lands it is not the way on, so it cannot skip the reveal it brought; then a tap is.
     */
    @Test
    fun `Next just after the reveal lands does nothing`() =
        runTest(dispatcher) {
            val questions = FakeQuestionRepository(QUESTION, NEXT_QUESTION)
            val viewModel = viewModel(questions)
            testScheduler.advanceUntilIdle()
            viewModel.choose(Side.A)
            testScheduler.runCurrent()
            val revealed = assertIs<PlayUiState.Revealed>(viewModel.state.value)

            testScheduler.advanceTimeBy(REVEAL_HOLD_MILLIS - 1)
            viewModel.next()
            testScheduler.runCurrent()
            assertEquals(revealed, viewModel.state.value, "just before the hold ends")
            assertEquals(listOf("next"), questions.calls)

            testScheduler.advanceTimeBy(1)
            testScheduler.runCurrent()
            viewModel.next()
            testScheduler.advanceUntilIdle()
            assertEquals(PlayUiState.Asking(NEXT_QUESTION), viewModel.state.value, "once it has")
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

    /** Nothing to show until the server says, rather than a placeholder zero. */
    @Test
    fun `the points are unknown until read and then the server's`() =
        runTest(dispatcher) {
            val players = FakePlayerRepository()
            val viewModel = viewModel(players = players)
            testScheduler.advanceUntilIdle()
            assertNull(viewModel.points.value)

            viewModel.refreshPoints()
            testScheduler.advanceUntilIdle()
            assertEquals(5, viewModel.points.value)

            // Read again every time, since they move on other screens meanwhile.
            players.answer = { statsWith(totalPoints = 6) }
            viewModel.refreshPoints()
            testScheduler.advanceUntilIdle()
            assertEquals(6, viewModel.points.value)
            assertEquals(2, players.reads)
        }

    @Test
    fun `a vote's answer moves the points to its total`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            testScheduler.advanceUntilIdle()
            viewModel.refreshPoints()
            testScheduler.advanceUntilIdle()

            viewModel.choose(Side.A)
            testScheduler.advanceUntilIdle()

            assertEquals(42, viewModel.points.value)
        }

    /** The read may have been answered before the vote was counted, so the vote's total stays. */
    @Test
    fun `a read answered after a vote does not put older points back`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val players = FakePlayerRepository()
            players.answer = {
                gate.await()
                statsWith(totalPoints = 5)
            }
            val viewModel = viewModel(players = players)
            testScheduler.advanceUntilIdle()
            viewModel.refreshPoints()
            testScheduler.advanceUntilIdle()

            viewModel.choose(Side.A)
            testScheduler.advanceUntilIdle()
            gate.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertEquals(42, viewModel.points.value)
        }

    @Test
    fun `a failed read keeps the points shown and the question as it was`() =
        runTest(dispatcher) {
            val players = FakePlayerRepository()
            val viewModel = viewModel(players = players)
            testScheduler.advanceUntilIdle()
            viewModel.choose(Side.A)
            testScheduler.advanceUntilIdle()

            players.answer = { throw WyrException(DomainError.NETWORK) }
            viewModel.refreshPoints()
            testScheduler.advanceUntilIdle()

            assertEquals(42, viewModel.points.value)
            assertEquals(PlayUiState.Revealed(QUESTION, OUTCOME), viewModel.state.value)
        }

    @Test
    fun `the categories shown are the ones the repository plays`() =
        runTest(dispatcher) {
            // The repository outlives the screen, so it can open on a feed already filtered.
            val questions = FakeQuestionRepository()
            questions.categories.value = setOf("ETHICS")
            val viewModel = viewModel(questions)

            assertEquals(setOf("ETHICS"), viewModel.categories.value.selected)
        }

    @Test
    fun `the categories are named from the list the app read last`() =
        runTest(dispatcher) {
            // Read by another screen, say: the repository keeps them for every screen.
            val categories = FakeCategoryRepository()
            categories.refresh()
            val viewModel = viewModel(categories = categories)

            assertEquals(FakeCategoryRepository.LISTED, viewModel.categories.value.known)
        }

    @Test
    fun `categories played from the Categories screen drop the question on screen and show one from them`() =
        runTest(dispatcher) {
            val questions = FakeQuestionRepository(servedFor = mapOf(setOf("FOOD") to FOOD_QUESTION))
            val viewModel = viewModel(questions)
            testScheduler.advanceUntilIdle()

            // As the Categories screen's Play sets them, with nothing of this ViewModel's asked.
            questions.setCategories(setOf("FOOD"))
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("next", "setCategories [FOOD]", "next"), questions.calls)
            assertEquals(PlayUiState.Asking(FOOD_QUESTION), viewModel.state.value)
            assertEquals(setOf("FOOD"), viewModel.categories.value.selected)
        }

    @Test
    fun `categories played from the Categories screen drop the answered question too`() =
        runTest(dispatcher) {
            val questions = FakeQuestionRepository(servedFor = mapOf(setOf("FOOD") to FOOD_QUESTION))
            val viewModel = viewModel(questions)
            testScheduler.advanceUntilIdle()
            viewModel.choose(Side.A)
            testScheduler.advanceUntilIdle()
            assertIs<PlayUiState.Revealed>(viewModel.state.value)

            questions.setCategories(setOf("FOOD"))
            testScheduler.advanceUntilIdle()

            assertEquals(PlayUiState.Asking(FOOD_QUESTION), viewModel.state.value)
        }

    @Test
    fun `categories played from the Categories screen after a vote lost to the network move on without it`() =
        runTest(dispatcher) {
            // As the picker's change does: the vote counts only if it landed the first time (§8d).
            val votes = RecordingVoteRepository(DomainError.NETWORK)
            val questions = FakeQuestionRepository(servedFor = mapOf(setOf("FOOD") to FOOD_QUESTION))
            val viewModel = viewModel(questions, votes = votes)
            testScheduler.advanceUntilIdle()
            viewModel.choose(Side.A)
            testScheduler.advanceUntilIdle()
            assertIs<PlayUiState.Failed>(viewModel.state.value)

            questions.setCategories(setOf("FOOD"))
            testScheduler.advanceUntilIdle()

            assertEquals(1, votes.callCount, "the lost vote is not sent again")
            assertEquals(PlayUiState.Asking(FOOD_QUESTION), viewModel.state.value)
        }

    @Test
    fun `categories played elsewhere while a like is in flight leave the question to it`() =
        runTest(dispatcher) {
            // One action at a time: the like lands on the question it was for, and the next question
            // is the new categories' since the change dropped the queue.
            val gate = CompletableDeferred<Unit>()
            val likes = FakeLikeRepository()
            likes.answer = { questionId, liked ->
                gate.await()
                QuestionLikes(questionId, likeCount = 1, likedByMe = liked)
            }
            val questions = FakeQuestionRepository(servedFor = mapOf(setOf("FOOD") to FOOD_QUESTION))
            val viewModel = viewModel(questions, likes = likes)
            testScheduler.advanceUntilIdle()
            viewModel.toggleLike()
            testScheduler.advanceUntilIdle()

            questions.setCategories(setOf("FOOD"))
            testScheduler.advanceUntilIdle()
            gate.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("next", "setCategories [FOOD]"), questions.calls)
            assertEquals(PlayUiState.Asking(QUESTION.copy(likeCount = 1, likedByMe = true)), viewModel.state.value)
            viewModel.skip()
            testScheduler.advanceUntilIdle()
            assertEquals(PlayUiState.Asking(FOOD_QUESTION), viewModel.state.value)
        }

    private fun viewModel(
        questions: QuestionRepository = FakeQuestionRepository(),
        votes: VoteRepository = FakeVoteRepository(),
        likes: LikeRepository = FakeLikeRepository(),
        players: PlayerRepository = FakePlayerRepository(),
        categories: CategoryRepository = FakeCategoryRepository(),
    ) = PlayViewModel(
        getNextQuestion = GetNextQuestion(questions, NoOpSessionRepository),
        castVote = CastVote(votes, NoOpSessionRepository),
        skipQuestion = SkipQuestion(questions, NoOpSessionRepository),
        setLike = SetLike(likes, NoOpSessionRepository),
        getPlayerStats = GetPlayerStats(players, NoOpSessionRepository),
        questions = questions,
        categoryList = categories,
    )

    private companion object {
        val QUESTION =
            Question(
                id = "q1",
                optionA = "Fly",
                optionB = "Turn invisible",
                categories = setOf("SUPERPOWERS"),
            )

        val NEXT_QUESTION =
            Question(
                id = "q2",
                optionA = "Always be cold",
                optionB = "Always be hot",
                categories = setOf("LIFESTYLE"),
            )

        val FOOD_QUESTION =
            Question(
                id = "f1",
                optionA = "Only eat soup",
                optionB = "Never eat soup again",
                categories = setOf("FOOD"),
            )

        val OUTCOME =
            VoteOutcome(
                yourSide = Side.A,
                tally = Tally(votesA = 7, votesB = 3),
                pointsAwarded = 17,
                totalPoints = 42,
            )

        fun statsWith(totalPoints: Int): PlayerStats =
            PlayerStats(
                totalPoints = totalPoints,
                answersGiven = 0,
                questionsAnswered = 0,
                cycle = 1,
                dueThisCycle = 10,
                likesReceived = 0,
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
        private val servedFor: Map<Set<String>, Question?> = emptyMap(),
    ) : QuestionRepository {
        private val served = served.toMutableList().ifEmpty { mutableListOf(QUESTION) }

        val skipped = mutableListOf<String>()

        /** Every fetch and every change of categories, in order, the categories in id order. */
        val calls = mutableListOf<String>()

        override val categories = MutableStateFlow<Set<String>>(emptySet())

        override suspend fun next(): Question {
            calls += "next"
            val selected = categories.value
            if (selected in servedFor) {
                return servedFor[selected] ?: throw WyrException(DomainError.OUT_OF_QUESTIONS)
            }
            return if (served.size > 1) served.removeAt(0) else served.first()
        }

        override suspend fun prefetch() = Unit

        override suspend fun setCategories(categories: Set<String>) {
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
        override val categories: StateFlow<Set<String>> = MutableStateFlow(emptySet())

        override suspend fun next(): Question = throw WyrException(error)

        override suspend fun prefetch() = Unit

        override suspend fun setCategories(categories: Set<String>) = Unit

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

    /** Answers every read of the stats with [answer], and counts them. */
    private class FakePlayerRepository : PlayerRepository {
        var answer: suspend () -> PlayerStats = { statsWith(totalPoints = 5) }

        var reads = 0

        override suspend fun stats(): PlayerStats {
            reads++
            return answer()
        }
    }

    /** The server's categories, [LISTED], none read until something reads them. */
    private class FakeCategoryRepository : CategoryRepository {
        override val categories = MutableStateFlow<List<Category>>(emptyList())

        override suspend fun refresh(): List<Category> = LISTED.also { categories.value = it }

        companion object {
            val LISTED =
                listOf(
                    Category(id = "FOOD", nameSr = "Храна", nameEn = "Food"),
                    Category(id = "ETHICS", nameSr = "Етика", nameEn = "Ethics"),
                    Category(id = "ABSURD", nameSr = "Апсурдно", nameEn = "Absurd"),
                )
        }
    }

    private object NoOpSessionRepository : SessionRepository {
        override suspend fun ensure(): String = "player-1"
    }
}
