package io.ntole.wyr.core.data.player

import io.ntole.wyr.core.data.BASE_URL
import io.ntole.wyr.core.data.FakeServer
import io.ntole.wyr.core.data.session
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.storeHolding
import io.ntole.wyr.core.domain.player.GetPlayerStats
import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.api.AuthApi
import io.ntole.wyr.core.network.api.PlayerApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultPlayerRepositoryTest {
    private val server = FakeServer()

    @Test
    fun `a first launch's stats are asked for once with the session just minted`() =
        runTest {
            val store = storeHolding(null)
            val client = WyrHttpClient.create(BASE_URL, store, server.engine)
            val sessions = DefaultSessionRepository(AuthApi(client), store)
            val getPlayerStats = GetPlayerStats(DefaultPlayerRepository(PlayerApi(client), sessions), sessions)

            val stats = getPlayerStats()

            // FakeServer.STATS, decoded through the real client, for the player just minted.
            assertEquals(
                PlayerStats(
                    totalPoints = 7,
                    answersGiven = 9,
                    questionsAnswered = 5,
                    cycle = 2,
                    dueThisCycle = 11,
                    likesReceived = 3,
                ),
                stats,
            )
            // Not refused and then recovered: the session was ensured before the stats were asked for.
            assertEquals(listOf<String?>("Bearer access-guest1"), server.statsSentAs)
        }

    @Test
    fun `stats read on a dead session are read again as a fresh guest`() =
        runTest {
            // The server has never heard of "a": the state after a dev server restarts.
            val store = storeHolding(session("a"))

            repositoryOver(store).stats()

            assertEquals(1, server.guestsMinted)
            assertEquals("guest1", store.read()?.playerId)
            // The fresh guest's stats, not the dead session's: the second read is the one answered.
            assertEquals(listOf<String?>("Bearer access-a", "Bearer access-guest1"), server.statsSentAs)
        }

    private fun repositoryOver(store: SessionStore): DefaultPlayerRepository {
        val client = WyrHttpClient.create(BASE_URL, store, server.engine)
        return DefaultPlayerRepository(PlayerApi(client), DefaultSessionRepository(AuthApi(client), store))
    }
}
