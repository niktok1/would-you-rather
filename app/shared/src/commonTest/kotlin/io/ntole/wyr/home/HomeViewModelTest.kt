package io.ntole.wyr.home

import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.home.GetHomePicks
import io.ntole.wyr.core.domain.home.HomePickRepository
import io.ntole.wyr.core.domain.home.PickOnHome
import io.ntole.wyr.core.domain.session.SessionRepository
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Home screen's two Play buttons (CLAUDE.md §8d, *Home picks*): read when shown, each tap counted. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val picks = FakePicks()
    private val viewModel by lazy { HomeViewModel(GetHomePicks(picks), PickOnHome(picks, NoOpSessionRepository)) }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the counts are none until read and then the server's each time Home is shown`() =
        runTest(dispatcher) {
            assertNull(viewModel.picks.value)

            viewModel.shown()
            testScheduler.advanceUntilIdle()
            assertEquals(Tally(votesA = 3, votesB = 1), viewModel.picks.value)

            picks.counts = Tally(votesA = 3, votesB = 5)
            viewModel.shown()
            testScheduler.advanceUntilIdle()

            assertEquals(Tally(votesA = 3, votesB = 5), viewModel.picks.value)
            assertEquals(2, picks.reads)
        }

    /** Nothing is said: the buttons start the game all the same. */
    @Test
    fun `a read that fails keeps what was read before`() =
        runTest(dispatcher) {
            viewModel.shown()
            testScheduler.advanceUntilIdle()

            picks.readFailure = DomainError.NETWORK
            viewModel.shown()
            testScheduler.advanceUntilIdle()

            assertEquals(Tally(votesA = 3, votesB = 1), viewModel.picks.value)
        }

    @Test
    fun `a tap counts its side and a tap not counted says nothing`() =
        runTest(dispatcher) {
            viewModel.pick(Side.A)
            testScheduler.advanceUntilIdle()
            picks.pickFailure = DomainError.RATE_LIMITED
            viewModel.pick(Side.B)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf(Side.A, Side.B), picks.picked)
            assertNull(viewModel.picks.value, "a tap's answer is not shown: Home is left by then")
        }

    private class FakePicks : HomePickRepository {
        var counts = Tally(votesA = 3, votesB = 1)
        var readFailure: DomainError? = null
        var pickFailure: DomainError? = null
        var reads = 0
        val picked = mutableListOf<Side>()

        override suspend fun counts(): Tally {
            reads++
            readFailure?.let { throw WyrException(it) }
            return counts
        }

        override suspend fun pick(side: Side): Tally {
            picked += side
            pickFailure?.let { throw WyrException(it) }
            return counts
        }
    }

    private object NoOpSessionRepository : SessionRepository {
        override suspend fun ensure(): String = "player-1"
    }
}
