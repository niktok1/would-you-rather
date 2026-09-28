package io.ntole.wyr.core.data.playgames

import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.data.BASE_URL
import io.ntole.wyr.core.data.FakeServer
import io.ntole.wyr.core.data.account.DefaultAccountRepository
import io.ntole.wyr.core.data.player.DefaultPlayerRepository
import io.ntole.wyr.core.data.session
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.session.PlayGamesSettled
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.network.InMemoryTokenStorage
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.api.AuthApi
import io.ntole.wyr.core.network.api.PlayerApi
import io.ntole.wyr.core.network.environment.WyrEnvironment
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Signing in with Play Games through the real client, against [FakeServer] (CLAUDE.md §8a). */
class DefaultPlayGamesRepositoryTest {
    private val server = FakeServer()
    private val storage = InMemoryTokenStorage()
    private val store = SessionStore(storage, WyrEnvironment.DEV)
    private val client = WyrHttpClient.create(BASE_URL, store, server.engine)
    private val sessions =
        DefaultSessionRepository(AuthApi(client), store, PlayGamesSettled(storage, WyrEnvironment.DEV))
    private val playGames = DefaultPlayGamesRepository(AuthApi(client), sessions)
    private val players = DefaultPlayerRepository(PlayerApi(client), sessions)

    @Test
    fun `a Play Games player linked to nobody is linked to the guest playing who keeps everything`() =
        runTest {
            sessions.ensure()
            server.playGamesCodes["code-1"] = "gp-1"
            assertFalse(playGames.isSettled(), "a fresh install is unsettled")

            val player = playGames.signIn("code-1")

            assertEquals("guest1", player)
            assertEquals<List<Pair<String?, String>>>(
                listOf("Bearer access-guest1" to "code-1"),
                server.playGamesSentAs,
            )
            assertEquals("guest1", store.read()?.playerId, "a new session of the same player")
            assertTrue(playGames.isSettled())
            assertTrue(players.stats().playGamesLinked)
            assertTrue(storage.read("wyr.playgames.settled.dev") != null, "kept for DEV's server alone")
        }

    @Test
    fun `a Play Games player linked already signs in as its player`() =
        runTest {
            sessions.ensure()
            server.playGamesLinks["gp-1"] = "linked-player"
            server.playGamesCodes["code-1"] = "gp-1"

            assertEquals("linked-player", playGames.signIn("code-1"))
            assertEquals("linked-player", store.read()?.playerId)
        }

    @Test
    fun `a code Google refuses changes nothing and settles nothing`() =
        runTest {
            sessions.ensure()
            val before = store.read()

            val refused = assertFailsWith<WyrException> { playGames.signIn("spent") }

            assertEquals(DomainError.PLAY_GAMES_CODE_REFUSED, refused.error)
            assertEquals(before, store.read())
            assertFalse(playGames.isSettled())
        }

    @Test
    fun `a server that cannot ask Google is PLAY_GAMES_UNAVAILABLE`() =
        runTest {
            sessions.ensure()
            server.refusePlayGamesWith = HttpStatusCode.BadGateway to ErrorCode.PLAY_GAMES_UNAVAILABLE

            val refused = assertFailsWith<WyrException> { playGames.signIn("code-1") }

            assertEquals(DomainError.PLAY_GAMES_UNAVAILABLE, refused.error)
            assertFalse(playGames.isSettled())
        }

    /** A dead session is a 401 before the code is spent, so the retry sends it as the fresh guest. */
    @Test
    fun `a sign-in on a dead session sends the code again as the fresh guest`() =
        runTest {
            store.write(session("dead"))
            server.playGamesCodes["code-1"] = "gp-1"

            assertEquals("guest1", playGames.signIn("code-1"))
            assertEquals(listOf("Bearer access-dead", "Bearer access-guest1"), server.playGamesSentAs.map { it.first })
            assertTrue(playGames.isSettled())
        }

    /**
     * The exchange with Google takes seconds, more after a cold start: a login or a logout landing
     * meanwhile is the player's later choice, which the answer does not undo. Each lands as its own
     * repository stores it, a login's session in place of the device's and a logout's none.
     */
    @Test
    fun `a sign-in answered after a login or a logout stores nothing`() =
        runTest {
            sessions.ensure()
            server.playGamesCodes["code-1"] = "gp-1"
            server.whilePlayGamesExchanges = { sessions.replace(session("bob-player")) }

            assertNull(playGames.signIn("code-1"))
            assertEquals(session("bob-player"), store.read())

            server.playGamesCodes["code-2"] = "gp-2"
            server.whilePlayGamesExchanges = { sessions.clear() }

            assertNull(playGames.signIn("code-2"))
            assertNull(store.read(), "logged out, the fresh guest minted by the next call")
        }

    /** A login and a logout are the player's doing, as a sign-in is; a dead session replaced is nobody's. */
    @Test
    fun `a login and a logout settle who plays here and a dead session forgets it`() =
        runTest {
            val accounts = DefaultAccountRepository(AuthApi(client), sessions)
            server.accounts["bob_1"] = "correct horse" to "bob-player"
            accounts.logIn("bob_1", "correct horse")
            assertTrue(playGames.isSettled(), "after a login")

            sessions.resetIfStill(store.read())
            assertFalse(playGames.isSettled(), "after the dead session was replaced")

            accounts.logOut()
            assertTrue(playGames.isSettled(), "after a logout")
            sessions.ensure()
            assertTrue(playGames.isSettled(), "the logout's fresh guest")
        }
}
