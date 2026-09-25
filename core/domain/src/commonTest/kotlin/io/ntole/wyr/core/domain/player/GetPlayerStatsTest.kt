package io.ntole.wyr.core.domain.player

import io.ntole.wyr.core.domain.session.SessionRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GetPlayerStatsTest {
    private val calls = mutableListOf<String>()

    @Test
    fun `the session is ensured before the stats are asked for`() =
        runTest {
            val getPlayerStats = GetPlayerStats(RecordingPlayers(calls), RecordingSessions(calls))

            assertEquals(STATS, getPlayerStats())
            assertEquals(listOf("ensure", "stats"), calls)
        }

    private class RecordingPlayers(
        private val calls: MutableList<String>,
    ) : PlayerRepository {
        override suspend fun stats(): PlayerStats {
            calls += "stats"
            return STATS
        }
    }

    private class RecordingSessions(
        private val calls: MutableList<String>,
    ) : SessionRepository {
        override suspend fun ensure(): String {
            calls += "ensure"
            return "p1"
        }

        override suspend fun currentPlayerId(): String = "p1"

        override suspend fun clear() = Unit
    }

    private companion object {
        val STATS =
            PlayerStats(
                playerId = "p1",
                totalPoints = 3,
                answersGiven = 3,
                questionsAnswered = 2,
                cycle = 1,
                dueThisCycle = 22,
                likesReceived = 0,
            )
    }
}
