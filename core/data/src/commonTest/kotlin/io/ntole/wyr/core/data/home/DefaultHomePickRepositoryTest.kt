package io.ntole.wyr.core.data.home

import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.data.BASE_URL
import io.ntole.wyr.core.data.PlayServer
import io.ntole.wyr.core.data.session
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.storeHolding
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.home.PickOnHome
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.home.HomePicksDto
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.api.AuthApi
import io.ntole.wyr.core.network.api.HomePickApi
import io.ntole.wyr.core.vote.OptionSide
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The Home screen's picks through the real client (CLAUDE.md §8d, *Home picks*). */
class DefaultHomePickRepositoryTest {
    private val server = PlayServer()

    @Test
    fun `the counts are read with no session and none is minted`() =
        runTest {
            server.picks = HomePicksDto(picksA = 30, picksB = 10)
            val store = storeHolding(null)

            val counts = repositoryOver(store).counts()

            assertEquals(Tally(votesA = 30, votesB = 10), counts)
            assertEquals(listOf<String?>(null), server.pickReadsSentAs, "no bearer")
            assertEquals(0, server.guestsMinted)
            assertNull(store.read())
        }

    @Test
    fun `a read that fails is the domain's failure`() =
        runTest {
            server.refusePickReadsWith = HttpStatusCode.TooManyRequests to ErrorCode.RATE_LIMITED

            val failure = assertFailsWith<WyrException> { repositoryOver(storeHolding(null)).counts() }

            assertEquals(DomainError.RATE_LIMITED, failure.error)
        }

    @Test
    fun `a first launch's tap goes out once with the session just minted and answers both counts`() =
        runTest {
            val store = storeHolding(null)
            val client = WyrHttpClient.create(BASE_URL, store, server.engine)
            val sessions = DefaultSessionRepository(AuthApi(client), store)
            val pick = PickOnHome(DefaultHomePickRepository(HomePickApi(client), sessions), sessions)

            val counts = pick(Side.B)

            assertEquals(Tally(votesA = 0, votesB = 1), counts)
            assertEquals(listOf<Pair<String?, OptionSide>>("Bearer access-guest1" to OptionSide.B), server.picksSentAs)
        }

    @Test
    fun `a tap on a dead session is sent again as a fresh guest`() =
        runTest {
            // The server has never heard of "a": the state after a dev server restarts.
            val store = storeHolding(session("a"))

            repositoryOver(store).pick(Side.A)

            assertEquals(
                listOf<Pair<String?, OptionSide>>(
                    "Bearer access-a" to OptionSide.A,
                    "Bearer access-guest1" to OptionSide.A,
                ),
                server.picksSentAs,
            )
            assertEquals("guest1", store.read()?.playerId)
        }

    private fun repositoryOver(store: SessionStore): DefaultHomePickRepository {
        val client = WyrHttpClient.create(BASE_URL, store, server.engine)
        return DefaultHomePickRepository(HomePickApi(client), DefaultSessionRepository(AuthApi(client), store))
    }
}
