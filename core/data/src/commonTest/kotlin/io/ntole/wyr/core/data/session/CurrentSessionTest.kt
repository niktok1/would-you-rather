package io.ntole.wyr.core.data.session

import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.data.BASE_URL
import io.ntole.wyr.core.data.FakeServer
import io.ntole.wyr.core.data.session
import io.ntole.wyr.core.data.storeHolding
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.api.AuthApi
import io.ntole.wyr.core.network.api.PlayerApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Every session the device stores, heard once each without minting anyone: what a push token and a
 * Play Games sign-in follow (CLAUDE.md §8a).
 */
class CurrentSessionTest {
    private val server = FakeServer()

    @Test
    fun `nothing stored is nobody and watching mints nobody`() =
        runTest {
            val sessions = repository(storeHolding(null))
            val heard = mutableListOf<String>()
            val watching = watch(sessions, heard)

            assertNull(sessions.current())
            assertEquals(emptyList(), heard)
            assertEquals(0, server.guestsMinted)
            watching.cancel()
        }

    @Test
    fun `the session stored first and every one after are heard once each`() =
        runTest {
            val sessions = repository(storeHolding(session("a")))
            val heard = mutableListOf<String>()
            val watching = watch(sessions, heard)

            sessions.replace(session("b"))
            // Another session of the same player is a new one all the same.
            sessions.replace(session("b"))
            sessions.clear()
            sessions.ensure()
            sessions.resetIfStill(sessions.storedSession())

            assertEquals(listOf("a", "b", "b", "guest1", "guest2"), heard)
            assertEquals("guest2", sessions.current())
            watching.cancel()
        }

    /** A refresh keeps its session: rotating its tokens is no new one. */
    @Test
    fun `a refresh is not a session of its own`() =
        runTest {
            val store = storeHolding(null)
            val client = WyrHttpClient.create(BASE_URL, store, server.engine)
            val sessions = DefaultSessionRepository(AuthApi(client), store)
            sessions.ensure()
            val heard = mutableListOf<String>()
            val watching = watch(sessions, heard)

            // The stored access token is refused once, and the plugin refreshes it.
            store.write(assertStored(store.read()).copy(accessToken = "access-expired"))
            PlayerApi(client).me()

            assertEquals(listOf("guest1"), heard)
            assertEquals(1, server.refreshesSent)
            watching.cancel()
        }

    /**
     * Collects [sessions] into [heard] as each is stored, in the thread that stores it: the stores run
     * on the client's threads, so the test's own dispatcher would hear them only later.
     */
    private fun TestScope.watch(
        sessions: DefaultSessionRepository,
        heard: MutableList<String>,
    ): Job = backgroundScope.launch(Dispatchers.Unconfined) { sessions.sessions.collect { heard += it } }

    private fun repository(store: SessionStore): DefaultSessionRepository =
        DefaultSessionRepository(AuthApi(WyrHttpClient.create(BASE_URL, store, server.engine)), store)

    private fun assertStored(session: SessionDto?): SessionDto = assertNotNull(session, "no session stored")
}
