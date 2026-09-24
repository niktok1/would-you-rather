package io.ntole.wyr.core.network

import io.ntole.wyr.core.network.environment.WyrEnvironment
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * One storage holding every environment's session, as desktop, iOS and web have (CLAUDE.md §8e):
 * a build for one server must neither see nor replace the session another server issued.
 */
class SessionStoreTest {
    private val storage = InMemoryTokenStorage()

    @Test
    fun `each environment keeps its own session in one storage`() =
        runTest {
            val stores = WyrEnvironment.entries.associateWith { SessionStore(storage, it) }
            stores.forEach { (environment, store) -> store.write(session(environment.name)) }

            stores.forEach { (environment, store) ->
                assertEquals(environment.name, store.read()?.playerId, environment.name)
            }
        }

    @Test
    fun `clearing one environment's session leaves the others'`() =
        runTest {
            val dev = SessionStore(storage, WyrEnvironment.DEV)
            val prod = SessionStore(storage, WyrEnvironment.PROD)
            dev.write(session("dev"))
            prod.write(session("prod"))

            dev.clear()

            assertNull(dev.read())
            assertEquals("prod", prod.read()?.playerId)
        }

    @Test
    fun `prod reads the session stored before there were environments`() =
        runTest {
            storage.write("wyr.session", WyrJson.encodeToString(session("a")))

            assertEquals("a", SessionStore(storage, WyrEnvironment.PROD).read()?.playerId)
            assertNull(SessionStore(storage, WyrEnvironment.LOCAL).read())
            assertNull(SessionStore(storage, WyrEnvironment.DEV).read())
        }

    @Test
    fun `a new session for prod is written where it always was`() =
        runTest {
            SessionStore(storage, WyrEnvironment.PROD).write(session("a"))

            assertNotNull(storage.read("wyr.session"))
        }
}
