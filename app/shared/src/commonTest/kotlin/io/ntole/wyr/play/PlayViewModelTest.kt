package io.ntole.wyr.play

import io.ntole.wyr.analytics.RecordingAnalytics
import io.ntole.wyr.analytics.RecordingAnalytics.Recorded
import io.ntole.wyr.core.domain.analytics.AnalyticsEvent
import io.ntole.wyr.core.domain.analytics.AnalyticsProperty
import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.category.CategoryRepository
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.player.GetPlayerStats
import io.ntole.wyr.core.domain.player.PlayerRepository
import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.domain.question.GetNextQuestion
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.domain.question.SkipQuestion
import io.ntole.wyr.core.domain.reaction.QuestionReactions
import io.ntole.wyr.core.domain.reaction.Reaction
import io.ntole.wyr.core.domain.reaction.ReactionRepository
import io.ntole.wyr.core.domain.reaction.SetReaction
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
    private val analytics = RecordingAnalytics()

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

    /** How long the tap took goes with the vote, and a retry of it sends the first tap's time again. */
    @Test
    fun `a vote carries the time its tap took and its retry the same`() =
        runTest(dispatcher) {
            val votes = RecordingVoteRepository(DomainError.NETWORK)
            val viewModel = viewModel(votes = votes)
            testScheduler.advanceUntilIdle()
            testScheduler.advanceTimeBy(2_500)
            viewModel.choose(Side.A)
            testScheduler.advanceUntilIdle()
            testScheduler.advanceTimeBy(7_000)

            viewModel.retry()
            testScheduler.advanceUntilIdle()

            assertIs<PlayUiState.Revealed>(viewModel.state.value)
            assertEquals(listOf<Long?>(2_500, 2_500), votes.answerMillis)
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
    fun `the reaction counts show before answering`() =
        runTest(dispatcher) {
            val served = QUESTION.copy(likeCount = 3, dislikeCount = 2, myReaction = Reaction.LIKE)
            val viewModel = viewModel(questions = FakeQuestionRepository(served))

            testScheduler.advanceUntilIdle()

            // As the feed counted them, with nothing answered yet.
            val state = assertIs<PlayUiState.Asking>(viewModel.state.value)
            assertEquals(Triple(3, 2, Reaction.LIKE), state.question.reactions())
        }

    @Test
    fun `a like of the question being asked shows the server's counts`() =
        runTest(dispatcher) {
            val reactions = FakeReactionRepository()
            // Others react too, so the counts shown are the server's and not one more than before.
            reactions.answer = { questionId, reaction -> QuestionReactions(questionId, 7, 2, reaction) }
            val votes = RecordingVoteRepository()
            val viewModel = viewModel(FakeQuestionRepository(QUESTION.copy(likeCount = 3)), votes, reactions)
            testScheduler.advanceUntilIdle()

            viewModel.react(Reaction.LIKE)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf(QUESTION.id to Reaction.LIKE), reactions.sent)
            assertEquals(
                PlayUiState.Asking(QUESTION.held(Reaction.LIKE, likes = 7, dislikes = 2)),
                viewModel.state.value,
            )
            assertEquals(0, votes.callCount, "a reaction is no answer")
        }

    @Test
    fun `a like then taking it back each put the server's answer on the question`() =
        runTest(dispatcher) {
            val reactions = FakeReactionRepository()
            val viewModel = viewModel(reactions = reactions)
            testScheduler.advanceUntilIdle()

            viewModel.react(Reaction.LIKE)
            testScheduler.advanceUntilIdle()
            assertEquals(PlayUiState.Asking(QUESTION.held(Reaction.LIKE)), viewModel.state.value)

            viewModel.react(Reaction.NONE)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf(QUESTION.id to Reaction.LIKE, QUESTION.id to Reaction.NONE), reactions.sent)
            assertEquals(PlayUiState.Asking(QUESTION), viewModel.state.value)
        }

    @Test
    fun `a dislike replaces a like on the question as the server answers`() =
        runTest(dispatcher) {
            val reactions = FakeReactionRepository()
            val viewModel = viewModel(reactions = reactions)
            testScheduler.advanceUntilIdle()

            viewModel.react(Reaction.LIKE)
            testScheduler.advanceUntilIdle()
            viewModel.react(Reaction.DISLIKE)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf(QUESTION.id to Reaction.LIKE, QUESTION.id to Reaction.DISLIKE), reactions.sent)
            assertEquals(PlayUiState.Asking(QUESTION.held(Reaction.DISLIKE)), viewModel.state.value)
        }

    @Test
    fun `a failed reaction leaves the question as it was and shows the error`() =
        runTest(dispatcher) {
            val reactions = FakeReactionRepository()
            var lost = false
            reactions.answer = { questionId, reaction ->
                if (!lost) {
                    lost = true
                    throw WyrException(DomainError.NETWORK)
                }
                heldOn(questionId, reaction)
            }
            val viewModel = viewModel(reactions = reactions)
            testScheduler.advanceUntilIdle()

            viewModel.react(Reaction.DISLIKE)
            testScheduler.advanceUntilIdle()
            assertEquals(PlayUiState.Asking(QUESTION, reactionError = DomainError.NETWORK), viewModel.state.value)

            viewModel.react(Reaction.DISLIKE)
            testScheduler.advanceUntilIdle()

            // A dislike both times: the first may have landed, and the server holds it once.
            assertEquals(listOf(QUESTION.id to Reaction.DISLIKE, QUESTION.id to Reaction.DISLIKE), reactions.sent)
            assertEquals(PlayUiState.Asking(QUESTION.held(Reaction.DISLIKE)), viewModel.state.value)
        }

    @Test
    fun `reactions answered for another question are not put on the one on screen`() =
        runTest(dispatcher) {
            val reactions = FakeReactionRepository()
            reactions.answer = { _, reaction -> QuestionReactions("q9", 5, 1, reaction) }
            val viewModel = viewModel(reactions = reactions)
            testScheduler.advanceUntilIdle()

            viewModel.react(Reaction.LIKE)
            testScheduler.advanceUntilIdle()

            assertEquals(PlayUiState.Asking(QUESTION), viewModel.state.value)
        }

    @Test
    fun `a reaction works after answering and keeps the reveal`() =
        runTest(dispatcher) {
            val reactions = FakeReactionRepository()
            val viewModel = viewModel(reactions = reactions)
            testScheduler.advanceUntilIdle()
            viewModel.choose(Side.B)
            testScheduler.advanceUntilIdle()

            viewModel.react(Reaction.LIKE)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf(QUESTION.id to Reaction.LIKE), reactions.sent)
            assertEquals(
                PlayUiState.Revealed(QUESTION.held(Reaction.LIKE), OUTCOME.copy(yourSide = Side.B)),
                viewModel.state.value,
            )
        }

    @Test
    fun `nothing else goes while a reaction is in flight`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val reactions = FakeReactionRepository()
            reactions.answer = { questionId, reaction ->
                gate.await()
                heldOn(questionId, reaction)
            }
            val questions = FakeQuestionRepository(QUESTION, NEXT_QUESTION)
            val votes = RecordingVoteRepository()
            val viewModel = viewModel(questions, votes, reactions)
            testScheduler.advanceUntilIdle()

            viewModel.react(Reaction.LIKE)
            testScheduler.advanceUntilIdle()
            viewModel.choose(Side.A)
            viewModel.skip()
            viewModel.react(Reaction.DISLIKE)
            testScheduler.advanceUntilIdle()

            assertEquals(0, votes.callCount)
            assertEquals(emptyList(), questions.skipped)
            assertEquals(1, reactions.sent.size)
            gate.complete(Unit)
            testScheduler.advanceUntilIdle()
            assertEquals(PlayUiState.Asking(QUESTION.held(Reaction.LIKE)), viewModel.state.value)
        }

    @Test
    fun `a reaction while the vote is in flight does nothing`() =
        runTest(dispatcher) {
            val reactions = FakeReactionRepository()
            val viewModel = viewModel(reactions = reactions)
            testScheduler.advanceUntilIdle()

            viewModel.choose(Side.A)
            viewModel.react(Reaction.LIKE)
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList(), reactions.sent)
            assertEquals(PlayUiState.Revealed(QUESTION, OUTCOME), viewModel.state.value)
        }

    /** One action at a time: the reveal waits for its reaction, which then lands on it. */
    @Test
    fun `Next while a reaction is in flight does nothing`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val reactions = FakeReactionRepository()
            reactions.answer = { questionId, reaction ->
                gate.await()
                heldOn(questionId, reaction)
            }
            val questions = FakeQuestionRepository(QUESTION, NEXT_QUESTION)
            val viewModel = viewModel(questions, reactions = reactions)
            testScheduler.advanceUntilIdle()
            viewModel.choose(Side.A)
            testScheduler.advanceUntilIdle()
            viewModel.react(Reaction.LIKE)
            testScheduler.advanceUntilIdle()

            viewModel.next()
            testScheduler.advanceUntilIdle()
            gate.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertEquals(PlayUiState.Revealed(QUESTION.held(Reaction.LIKE), OUTCOME), viewModel.state.value)
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
            // The player moved on instead of trying again: the vote counts only if it landed the first
            // time, and nothing can pay for it twice (CLAUDE.md §8d, *Retry safety*).
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
    fun `categories played elsewhere while a reaction is in flight leave the question to it`() =
        runTest(dispatcher) {
            // One action at a time: the reaction lands on the question it was for, and the next question
            // is the new categories' since the change dropped the queue.
            val gate = CompletableDeferred<Unit>()
            val reactions = FakeReactionRepository()
            reactions.answer = { questionId, reaction ->
                gate.await()
                heldOn(questionId, reaction)
            }
            val questions = FakeQuestionRepository(servedFor = mapOf(setOf("FOOD") to FOOD_QUESTION))
            val viewModel = viewModel(questions, reactions = reactions)
            testScheduler.advanceUntilIdle()
            viewModel.react(Reaction.LIKE)
            testScheduler.advanceUntilIdle()

            questions.setCategories(setOf("FOOD"))
            testScheduler.advanceUntilIdle()
            gate.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("next", "setCategories [FOOD]"), questions.calls)
            assertEquals(PlayUiState.Asking(QUESTION.held(Reaction.LIKE)), viewModel.state.value)
            viewModel.skip()
            testScheduler.advanceUntilIdle()
            assertEquals(PlayUiState.Asking(FOOD_QUESTION), viewModel.state.value)
        }

    /** Not next, which waits out the reveal's first half second: a selection played loads at once. */
    @Test
    fun `categories played from the Categories screen just after a reveal lands show one from them`() =
        runTest(dispatcher) {
            val questions = FakeQuestionRepository(servedFor = mapOf(setOf("FOOD") to FOOD_QUESTION))
            val viewModel = viewModel(questions)
            testScheduler.advanceUntilIdle()
            viewModel.choose(Side.A)
            testScheduler.runCurrent()
            assertIs<PlayUiState.Revealed>(viewModel.state.value)

            questions.setCategories(setOf("FOOD"))
            testScheduler.runCurrent()

            assertEquals(listOf("next", "setCategories [FOOD]", "next"), questions.calls)
            assertEquals(PlayUiState.Asking(FOOD_QUESTION), viewModel.state.value)
        }

    /** Where a selection with nothing to serve leaves the player (CLAUDE.md §8d, *Categories*). */
    @Test
    fun `categories played from the Categories screen from out of questions show one from them`() =
        runTest(dispatcher) {
            // Whatever the feed answers is the server's rule: here, no questions at all in ABSURD.
            val questions = FakeQuestionRepository(servedFor = mapOf(setOf("ABSURD") to null))
            questions.categories.value = setOf("ABSURD")
            val viewModel = viewModel(questions)
            testScheduler.advanceUntilIdle()
            assertEquals(PlayUiState.Failed(DomainError.OUT_OF_QUESTIONS), viewModel.state.value)

            questions.setCategories(emptySet())
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("next", "setCategories []", "next"), questions.calls)
            assertEquals(PlayUiState.Asking(QUESTION), viewModel.state.value)
        }

    /** The load in flight shows what it brings, and the question after it is the new selection's. */
    @Test
    fun `categories played elsewhere while a question loads leave the screen to that load`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val questions = FakeQuestionRepository(servedFor = mapOf(setOf("FOOD") to FOOD_QUESTION))
            questions.nextWaitsFor = gate
            val viewModel = viewModel(questions)
            testScheduler.advanceUntilIdle()
            assertEquals(PlayUiState.Loading, viewModel.state.value)

            questions.setCategories(setOf("FOOD"))
            testScheduler.advanceUntilIdle()
            questions.nextWaitsFor = null
            gate.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("next", "setCategories [FOOD]"), questions.calls, "one load, not two")
            assertEquals(PlayUiState.Asking(QUESTION), viewModel.state.value)
            viewModel.skip()
            testScheduler.advanceUntilIdle()
            assertEquals(PlayUiState.Asking(FOOD_QUESTION), viewModel.state.value)
        }

    /** The vote in flight lands on the question it was for and is revealed; the next is the new selection's. */
    @Test
    fun `categories played elsewhere while a vote is in flight leave the question to it`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val questions = FakeQuestionRepository(servedFor = mapOf(setOf("FOOD") to FOOD_QUESTION))
            val viewModel = viewModel(questions, votes = GatedVoteRepository(gate))
            testScheduler.advanceUntilIdle()
            viewModel.choose(Side.A)
            testScheduler.advanceUntilIdle()

            questions.setCategories(setOf("FOOD"))
            testScheduler.advanceUntilIdle()
            assertEquals(PlayUiState.Asking(QUESTION, isSubmitting = true), viewModel.state.value)
            gate.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("next", "setCategories [FOOD]"), questions.calls)
            assertEquals(PlayUiState.Revealed(QUESTION, OUTCOME), viewModel.state.value)
            viewModel.next()
            testScheduler.advanceUntilIdle()
            assertEquals(PlayUiState.Asking(FOOD_QUESTION), viewModel.state.value)
        }

    /** A skip shows Loading while it is sent, and the load after it is already the new selection's. */
    @Test
    fun `categories played elsewhere while a skip is in flight load one question from them`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val questions = FakeQuestionRepository(servedFor = mapOf(setOf("FOOD") to FOOD_QUESTION))
            questions.skipWaitsFor = gate
            val viewModel = viewModel(questions)
            testScheduler.advanceUntilIdle()
            viewModel.skip()
            testScheduler.advanceUntilIdle()

            questions.setCategories(setOf("FOOD"))
            testScheduler.advanceUntilIdle()
            assertEquals(PlayUiState.Loading, viewModel.state.value)
            gate.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("next", "setCategories [FOOD]", "next"), questions.calls, "one load, not two")
            assertEquals(PlayUiState.Asking(FOOD_QUESTION), viewModel.state.value)
        }

    /** Each question shown, by its id and categories, never its text (CLAUDE.md §8g). */
    @Test
    fun `a question asked is reported by id and categories`() =
        runTest(dispatcher) {
            viewModel()
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf(Recorded(AnalyticsEvent.QUESTION_SHOWN, about(QUESTION))),
                analytics.named(AnalyticsEvent.QUESTION_SHOWN),
            )
        }

    /** How long the player took, from the question shown to the tap, not to the server's answer. */
    @Test
    fun `an answer is reported with its side and how long it took`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val viewModel = viewModel(votes = GatedVoteRepository(gate))
            testScheduler.advanceUntilIdle()
            testScheduler.advanceTimeBy(1_234)

            viewModel.choose(Side.B)
            testScheduler.advanceTimeBy(5_000)
            gate.complete(Unit)
            testScheduler.advanceUntilIdle()

            val answered = analytics.named(AnalyticsEvent.QUESTION_ANSWERED).single()
            assertEquals(
                about(QUESTION) +
                    mapOf(
                        AnalyticsProperty.SIDE to "B",
                        AnalyticsProperty.ANSWER_MS to 1_234L,
                        AnalyticsProperty.AGREED_WITH_MAJORITY to false,
                    ),
                answered.properties,
            )
        }

    /** Time on Account, on the categories or in the background is none the player took over the question. */
    @Test
    fun `an answer counts only the time the Play screen was shown`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            testScheduler.advanceUntilIdle()
            testScheduler.advanceTimeBy(1_000)
            viewModel.screenHidden()
            testScheduler.advanceTimeBy(600_000)
            viewModel.screenShown()
            testScheduler.advanceTimeBy(500)

            viewModel.choose(Side.A)
            testScheduler.advanceUntilIdle()

            val answered = analytics.named(AnalyticsEvent.QUESTION_ANSWERED).single()
            assertEquals(1_500L, answered.properties[AnalyticsProperty.ANSWER_MS])
        }

    /** A question loaded while the screen is hidden, as one from the categories played is, counts from its showing. */
    @Test
    fun `a skip counts from when the Play screen showed its question`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.screenHidden()
            testScheduler.advanceUntilIdle()
            testScheduler.advanceTimeBy(5_000)
            viewModel.screenShown()
            testScheduler.advanceTimeBy(300)

            viewModel.skip()
            testScheduler.advanceUntilIdle()

            val skipped = analytics.named(AnalyticsEvent.QUESTION_SKIPPED).single()
            assertEquals(300L, skipped.properties[AnalyticsProperty.DURATION_MS])
        }

    @Test
    fun `a vote that failed is reported as shown and no answer`() =
        runTest(dispatcher) {
            val viewModel = viewModel(votes = FailingVoteRepository(DomainError.NETWORK))
            testScheduler.advanceUntilIdle()

            viewModel.choose(Side.A)
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList(), analytics.named(AnalyticsEvent.QUESTION_ANSWERED))
            assertEquals(listOf(shown(DomainError.NETWORK, "vote")), analytics.named(AnalyticsEvent.ERROR_SHOWN))
        }

    /** Already voted moves the player on and shows nothing, so it reports no failure. */
    @Test
    fun `a vote already counted is no failure shown`() =
        runTest(dispatcher) {
            val viewModel = viewModel(votes = FailingVoteRepository(DomainError.ALREADY_VOTED))
            testScheduler.advanceUntilIdle()

            viewModel.choose(Side.A)
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList(), analytics.named(AnalyticsEvent.ERROR_SHOWN))
        }

    @Test
    fun `a question that could not be read is reported as shown`() =
        runTest(dispatcher) {
            viewModel(questions = FailingQuestionRepository(DomainError.OUT_OF_QUESTIONS))
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf(shown(DomainError.OUT_OF_QUESTIONS, "question")),
                analytics.named(AnalyticsEvent.ERROR_SHOWN),
            )
        }

    @Test
    fun `a skip is reported with how long the question was shown and whether it was heard`() =
        runTest(dispatcher) {
            listOf(null to true, DomainError.NETWORK to false).forEach { (failure, recorded) ->
                analytics.recorded.clear()
                val viewModel = viewModel(questions = FakeQuestionRepository(skipFailure = failure))
                testScheduler.advanceUntilIdle()
                testScheduler.advanceTimeBy(700)

                viewModel.skip()
                testScheduler.advanceUntilIdle()

                assertEquals(
                    about(QUESTION) +
                        mapOf(AnalyticsProperty.DURATION_MS to 700L, AnalyticsProperty.RECORDED to recorded),
                    analytics.named(AnalyticsEvent.QUESTION_SKIPPED).single().properties,
                    "$failure",
                )
            }
        }

    @Test
    fun `a reaction set is reported with what it is and whether the question was answered`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            testScheduler.advanceUntilIdle()

            viewModel.react(Reaction.LIKE)
            testScheduler.advanceUntilIdle()
            viewModel.choose(Side.A)
            testScheduler.advanceUntilIdle()
            viewModel.react(Reaction.NONE)
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf("like" to false, "none" to true),
                analytics.named(AnalyticsEvent.REACTION_SET).map {
                    it.properties[AnalyticsProperty.REACTION] to it.properties[AnalyticsProperty.ANSWERED]
                },
            )
        }

    @Test
    fun `a reaction that failed is reported as shown`() =
        runTest(dispatcher) {
            val reactions = FakeReactionRepository()
            reactions.answer = { _, _ -> throw WyrException(DomainError.RATE_LIMITED) }
            val viewModel = viewModel(reactions = reactions)
            testScheduler.advanceUntilIdle()

            viewModel.react(Reaction.DISLIKE)
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList(), analytics.named(AnalyticsEvent.REACTION_SET))
            assertEquals(
                listOf(shown(DomainError.RATE_LIMITED, "reaction")),
                analytics.named(AnalyticsEvent.ERROR_SHOWN),
            )
        }

    private fun about(question: Question): Map<String, Any?> =
        mapOf(
            AnalyticsProperty.QUESTION_ID to question.id,
            AnalyticsProperty.CATEGORIES to question.categories.toList(),
        )

    private fun shown(
        error: DomainError,
        action: String,
    ) = Recorded(
        AnalyticsEvent.ERROR_SHOWN,
        mapOf(
            AnalyticsProperty.CODE to error.name,
            AnalyticsProperty.ACTION to action,
        ),
    )

    private fun viewModel(
        questions: QuestionRepository = FakeQuestionRepository(),
        votes: VoteRepository = FakeVoteRepository(),
        reactions: ReactionRepository = FakeReactionRepository(),
        players: PlayerRepository = FakePlayerRepository(),
        categories: CategoryRepository = FakeCategoryRepository(),
    ) = PlayViewModel(
        getNextQuestion = GetNextQuestion(questions, NoOpSessionRepository),
        castVote = CastVote(votes, NoOpSessionRepository),
        skipQuestion = SkipQuestion(questions, NoOpSessionRepository),
        setReaction = SetReaction(reactions, NoOpSessionRepository),
        getPlayerStats = GetPlayerStats(players, NoOpSessionRepository),
        questions = questions,
        categoryList = categories,
        analytics = analytics,
        timeSource = dispatcher.scheduler.timeSource,
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

        fun statsWith(totalPoints: Int): PlayerStats = PlayerStats(totalPoints = totalPoints, questionsAnswered = 0)

        /** Where [questionId]'s reactions stand when only this player holds [reaction]. */
        fun heldOn(
            questionId: String,
            reaction: Reaction,
        ) = QuestionReactions(
            questionId,
            likeCount = if (reaction == Reaction.LIKE) 1 else 0,
            dislikeCount = if (reaction == Reaction.DISLIKE) 1 else 0,
            myReaction = reaction,
        )

        /** This question with the player holding [reaction], and these counts, only theirs by default. */
        fun Question.held(
            reaction: Reaction,
            likes: Int = if (reaction == Reaction.LIKE) 1 else 0,
            dislikes: Int = if (reaction == Reaction.DISLIKE) 1 else 0,
        ) = copy(likeCount = likes, dislikeCount = dislikes, myReaction = reaction)

        /** A question's like count, dislike count and what the player thinks of it. */
        fun Question.reactions(): Triple<Int, Int, Reaction> = Triple(likeCount, dislikeCount, myReaction)
    }

    /**
     * Serves [served] in order, then the last of them again and again, or, while the categories
     * selected are a key of [servedFor], that key's question every time, and none is out of
     * questions: chosen as the fetch starts, and handed over once [nextWaitsFor], if set, completes.
     * Records every skip, and refuses each with [skipFailure] when there is one, once [skipWaitsFor],
     * if set, completes.
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

        var nextWaitsFor: CompletableDeferred<Unit>? = null

        var skipWaitsFor: CompletableDeferred<Unit>? = null

        override val categories = MutableStateFlow<Set<String>>(emptySet())

        override suspend fun next(): Question {
            calls += "next"
            val selected = categories.value
            val question =
                if (selected in servedFor) {
                    servedFor[selected] ?: throw WyrException(DomainError.OUT_OF_QUESTIONS)
                } else if (served.size > 1) {
                    served.removeAt(0)
                } else {
                    served.first()
                }
            nextWaitsFor?.await()
            return question
        }

        override suspend fun prefetch() = Unit

        override suspend fun setCategories(categories: Set<String>) {
            calls += "setCategories ${categories.sorted()}"
            this.categories.value = categories
        }

        override suspend fun skip(questionId: String) {
            skipped += questionId
            skipWaitsFor?.await()
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
            answerMillis: Long?,
        ): VoteOutcome = OUTCOME.copy(yourSide = side)
    }

    /** Answers every vote once [gate] completes, as a slow server does. */
    private class GatedVoteRepository(
        private val gate: CompletableDeferred<Unit>,
    ) : VoteRepository {
        override suspend fun cast(
            questionId: String,
            side: Side,
            attempt: AttemptId,
            answerMillis: Long?,
        ): VoteOutcome {
            gate.await()
            return OUTCOME.copy(yourSide = side)
        }
    }

    /** Records every vote, and refuses the first ones with [failures], in order. */
    private class RecordingVoteRepository(
        vararg failures: DomainError,
    ) : VoteRepository {
        private val failures = failures.toMutableList()

        val attempts = mutableListOf<AttemptId>()

        val sides = mutableListOf<Side>()

        /** How long each vote's answer took, as sent. */
        val answerMillis = mutableListOf<Long?>()

        val callCount: Int get() = attempts.size

        override suspend fun cast(
            questionId: String,
            side: Side,
            attempt: AttemptId,
            answerMillis: Long?,
        ): VoteOutcome {
            attempts += attempt
            sides += side
            this.answerMillis += answerMillis
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
            answerMillis: Long?,
        ): VoteOutcome = throw WyrException(error)
    }

    /**
     * Answers every reaction as the server would set it on a question nobody else reacts to, and
     * records it.
     */
    private class FakeReactionRepository : ReactionRepository {
        var answer: suspend (
            String,
            Reaction,
        ) -> QuestionReactions = { questionId, reaction -> heldOn(questionId, reaction) }

        /** The question and the reaction of every reaction sent, in order. */
        val sent = mutableListOf<Pair<String, Reaction>>()

        override suspend fun setReaction(
            questionId: String,
            reaction: Reaction,
        ): QuestionReactions {
            sent += questionId to reaction
            return answer(questionId, reaction)
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
