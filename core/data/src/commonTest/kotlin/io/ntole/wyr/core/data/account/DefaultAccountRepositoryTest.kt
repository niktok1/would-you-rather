package io.ntole.wyr.core.data.account

import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.auth.LoginRequest
import io.ntole.wyr.core.auth.RegisterRequest
import io.ntole.wyr.core.data.BASE_URL
import io.ntole.wyr.core.data.FakeServer
import io.ntole.wyr.core.data.player.DefaultPlayerRepository
import io.ntole.wyr.core.data.session
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.storeHolding
import io.ntole.wyr.core.domain.account.RegisterAccount
import io.ntole.wyr.core.domain.analytics.Analytics
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.player.GetPlayerStats
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.api.AuthApi
import io.ntole.wyr.core.network.api.PlayerApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Registering, logging in and logging out through the real client, against [FakeServer]. */
class DefaultAccountRepositoryTest {
    private val server = FakeServer()

    @Test
    fun `a registration keeps the player and the session they had`() =
        runTest {
            val device = Device(storeHolding(null))
            device.sessions.ensure()
            val before = device.store.read()

            val username = device.accounts.register("Bob_1", "correct horse")

            assertEquals("bob_1", username)
            assertEquals(before, device.store.read(), "the same session: nothing else changes")
            assertEquals(1, server.guestsMinted)
            assertEquals<List<Pair<String?, RegisterRequest>>>(
                listOf("Bearer access-guest1" to RegisterRequest("Bob_1", "correct horse")),
                server.registrationsSentAs,
            )
            // The guest's points and all are the account's now, and the stats name it.
            assertEquals("bob_1", device.stats().username)
            assertEquals("Bearer access-guest1", server.statsSentAs.last(), "read as the guest")
        }

    @Test
    fun `a first launch registers the guest it mints and sends the registration once`() =
        runTest {
            val device = Device(storeHolding(null))

            val username = RegisterAccount(device.accounts, device.sessions, Analytics.None)("Bob_1", "correct horse")

            assertEquals("bob_1", username)
            // Not refused and then recovered: the session was ensured before the registration went.
            assertEquals(listOf("Bearer access-guest1"), server.registrationsSentAs.map { it.first })
        }

    @Test
    fun `a registration on a dead session registers the fresh guest minted for it`() =
        runTest {
            // The server has never heard of "a": the state after a dev server restarts.
            val device = Device(storeHolding(session("a")))

            device.accounts.register("bob_1", "correct horse")

            assertEquals(listOf("Bearer access-a", "Bearer access-guest1"), server.registrationsSentAs.map { it.first })
            assertEquals("guest1", device.store.read()?.playerId)
            assertEquals("correct horse" to "guest1", server.accounts["bob_1"])
        }

    @Test
    fun `a taken username is USERNAME_TAKEN and the session stays as it was`() =
        runTest {
            server.accounts["bob_1"] = "their password" to "someone"
            val device = Device(storeHolding(null))
            device.sessions.ensure()
            val before = device.store.read()

            val refused = assertFailsWith<WyrException> { device.accounts.register("BOB_1", "correct horse") }

            assertEquals(DomainError.USERNAME_TAKEN, refused.error)
            assertEquals(before, device.store.read())
            assertEquals(1, server.guestsMinted)
        }

    @Test
    fun `a login stores the account's new session in place of the guest's`() =
        runTest {
            val phone = Device(storeHolding(null))
            phone.sessions.ensure()
            phone.accounts.register("Bob_1", "correct horse")
            val phoneSession = phone.store.read()
            val tablet = Device(storeHolding(null))
            tablet.sessions.ensure()

            tablet.accounts.logIn("BOB_1", "correct horse")

            // The tablet plays as the account from its very next call; its guest is left behind.
            assertEquals("guest1", tablet.store.read()?.playerId)
            assertEquals("bob_1", tablet.stats().username)
            assertEquals("Bearer access-guest1", server.statsSentAs.last(), "read as the account")
            // A session of the tablet's own: the phone's is left alone.
            assertEquals(phoneSession, phone.store.read())
            // Sent with no bearer: a login reads none.
            assertEquals<List<Pair<String?, LoginRequest>>>(
                listOf(null to LoginRequest("BOB_1", "correct horse")),
                server.loginsSentAs,
            )
            assertEquals(2, server.guestsMinted)
        }

    @Test
    fun `a refused login is INVALID_LOGIN and refreshes and replaces nothing`() =
        runTest {
            server.accounts["bob_1"] = "correct horse" to "someone"
            val device = Device(storeHolding(null))
            device.sessions.ensure()
            val before = device.store.read()

            // A wrong password and a name with no account are one and the same refusal.
            listOf("bob_1" to "wrong horse", "nobody" to "correct horse").forEach { (username, password) ->
                val refused = assertFailsWith<WyrException> { device.accounts.logIn(username, password) }
                assertEquals(DomainError.INVALID_LOGIN, refused.error, username)
            }

            // Its 401 is no expired token: the bearer plugin refreshed nothing, recovery minted nothing.
            assertEquals(0, server.refreshesSent)
            assertEquals(1, server.guestsMinted)
            assertEquals(2, server.loginsSentAs.size, "each sent once")
            assertEquals(before, device.store.read())
        }

    @Test
    fun `a logout tells the server and the next call plays as a fresh guest`() =
        runTest {
            val device = Device(storeHolding(null))
            device.sessions.ensure()
            device.accounts.register("bob_1", "correct horse")

            device.accounts.logOut()

            assertEquals<List<String?>>(listOf("Bearer access-guest1"), server.logoutsSentAs)
            assertNull(device.store.read())
            val next = GetPlayerStats(device.players, device.sessions)()
            assertEquals("guest2", device.store.read()?.playerId)
            assertEquals("Bearer access-guest2", server.statsSentAs.last(), "read as the fresh guest")
            assertNull(next.username)
        }

    @Test
    fun `a logout the server refuses still logs this device out`() =
        runTest {
            server.refuseLogoutsWith = HttpStatusCode.InternalServerError to ErrorCode.INTERNAL
            val device = Device(storeHolding(null))
            device.sessions.ensure()

            device.accounts.logOut()

            assertEquals(1, server.logoutsSentAs.size)
            assertNull(device.store.read())
        }

    @Test
    fun `a logout with no session sends nothing`() =
        runTest {
            Device(storeHolding(null)).accounts.logOut()

            assertEquals(emptyList(), server.logoutsSentAs)
        }

    /** One installation of the app: its own store, over the one [server]. */
    private inner class Device(
        val store: SessionStore,
    ) {
        private val client = WyrHttpClient.create(BASE_URL, store, server.engine)
        val sessions = DefaultSessionRepository(AuthApi(client), store)
        val accounts = DefaultAccountRepository(AuthApi(client), sessions)
        val players = DefaultPlayerRepository(PlayerApi(client), sessions)

        suspend fun stats() = players.stats()
    }
}
