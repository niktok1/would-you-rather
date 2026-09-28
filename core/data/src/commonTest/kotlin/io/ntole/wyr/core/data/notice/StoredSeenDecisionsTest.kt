package io.ntole.wyr.core.data.notice

import io.ntole.wyr.core.domain.notice.SeenDecisions
import io.ntole.wyr.core.network.InMemoryTokenStorage
import io.ntole.wyr.core.network.environment.WyrEnvironment
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The decisions a player has seen, kept on the device for each environment's server (CLAUDE.md §8e). */
class StoredSeenDecisionsTest {
    private val storage = InMemoryTokenStorage()

    @Test
    fun `what was seen reads back as it was kept`() =
        runTest {
            val store = StoredSeenDecisions(storage, WyrEnvironment.DEV)
            assertNull(store.read(), "nothing kept yet")

            store.write(SeenDecisions("p1", setOf("q2", "q1")))
            assertEquals(SeenDecisions("p1", setOf("q1", "q2")), store.read())

            store.write(SeenDecisions("p1", emptySet()))
            assertEquals(SeenDecisions("p1", emptySet()), store.read(), "none seen is not nothing kept")
            assertEquals("p1", storage.read("wyr.decisions.seen.dev"))
        }

    @Test
    fun `each environment keeps its own`() =
        runTest {
            StoredSeenDecisions(storage, WyrEnvironment.DEV).write(SeenDecisions("dev-player", setOf("q1")))

            assertNull(StoredSeenDecisions(storage, WyrEnvironment.PROD).read())
            assertNull(StoredSeenDecisions(storage, WyrEnvironment.LOCAL).read())
        }
}
