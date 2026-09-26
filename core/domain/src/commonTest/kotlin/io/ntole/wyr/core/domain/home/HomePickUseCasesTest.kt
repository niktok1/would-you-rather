package io.ntole.wyr.core.domain.home

import io.ntole.wyr.core.domain.session.SessionRepository
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Home screen's picks (CLAUDE.md §8d, *Home picks*): a read needs no session, a tap does. */
class HomePickUseCasesTest {
    private val calls = mutableListOf<String>()
    private val picks = RecordingPicks(calls)
    private val session = RecordingSessions(calls)

    @Test
    fun `the counts are read with no session ensured`() =
        runTest {
            val counts = GetHomePicks(picks)()

            assertEquals(Tally(votesA = 3, votesB = 1), counts)
            assertEquals(listOf("counts"), calls)
        }

    @Test
    fun `a tap ensures the session and then counts its side`() =
        runTest {
            val counts = PickOnHome(picks, session)(Side.B)

            assertEquals(Tally(votesA = 3, votesB = 2), counts)
            assertEquals(listOf("ensure", "pick B"), calls)
        }

    private class RecordingPicks(
        private val calls: MutableList<String>,
    ) : HomePickRepository {
        override suspend fun counts(): Tally {
            calls += "counts"
            return Tally(votesA = 3, votesB = 1)
        }

        override suspend fun pick(side: Side): Tally {
            calls += "pick $side"
            return Tally(votesA = 3, votesB = 2)
        }
    }

    private class RecordingSessions(
        private val calls: MutableList<String>,
    ) : SessionRepository {
        override suspend fun ensure(): String {
            calls += "ensure"
            return "p1"
        }
    }
}
