package io.ntole.wyr.core.network

import io.ntole.wyr.core.network.environment.WyrEnvironment
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One secret store and one local storage holding every environment's, as iOS has: its environments'
 * builds share one Keychain (CLAUDE.md §8e). A build for one server must never read, and so never
 * send, the secret another server issued.
 */
class RecoverySecretStoreTest {
    private val secrets = MapSecretStorage()
    private val local = InMemoryTokenStorage()

    @Test
    fun `each environment keeps its own secret in one store`() =
        runTest {
            val stores = WyrEnvironment.entries.associateWith(::store)
            stores.forEach { (environment, store) -> store.write("secret-${environment.name}") }

            stores.forEach { (environment, store) -> assertEquals("secret-${environment.name}", store.read()) }
        }

    @Test
    fun `clearing one environment's secret leaves the others'`() =
        runTest {
            store(WyrEnvironment.DEV).write("dev")
            store(WyrEnvironment.PROD).write("prod")

            store(WyrEnvironment.DEV).clear()

            assertNull(store(WyrEnvironment.DEV).read())
            assertEquals("prod", store(WyrEnvironment.PROD).read())
        }

    /** The names Block Store and the Keychain keep secrets under: renaming one strands them. */
    @Test
    fun `each secret is kept under its environment's own name and in the secret store alone`() =
        runTest {
            WyrEnvironment.entries.forEach { store(it).write(it.name) }

            assertEquals(
                mapOf("wyr.recovery.local" to "LOCAL", "wyr.recovery.dev" to "DEV", "wyr.recovery.prod" to "PROD"),
                secrets.values,
            )
            WyrEnvironment.entries.forEach { assertNull(local.read(RecoverySecretStore.secretKeyFor(it))) }
        }

    @Test
    fun `a secret is stored again under its environment's own name`() =
        runTest {
            store(WyrEnvironment.DEV).backUp("dev")

            assertEquals(listOf("wyr.recovery.dev" to "dev"), secrets.backedUp)
        }

    @Test
    fun `failed requests are counted for one player and another player's count reads as none`() =
        runTest {
            val store = store(WyrEnvironment.DEV)
            store.countFailedRequest("p1")
            store.countFailedRequest("p1")

            assertEquals(2, store.failedRequests("p1"))
            assertEquals(0, store.failedRequests("p2"))

            store.countFailedRequest("p2")

            assertEquals(1, store.failedRequests("p2"))
            assertEquals(0, store.failedRequests("p1"))
        }

    @Test
    fun `failed requests outlive the store as they must a relaunch until they are cleared`() =
        runTest {
            store(WyrEnvironment.DEV).countFailedRequest("p1")

            assertEquals(1, store(WyrEnvironment.DEV).failedRequests("p1"))
            assertEquals(0, store(WyrEnvironment.PROD).failedRequests("p1"))

            store(WyrEnvironment.DEV).clearFailedRequests()

            assertEquals(0, store(WyrEnvironment.DEV).failedRequests("p1"))
        }

    /** Beside the session, which never leaves the phone (CLAUDE.md §8a), and never beside the secret. */
    @Test
    fun `failed requests are counted in the local storage and never in the secret store`() =
        runTest {
            store(WyrEnvironment.PROD).countFailedRequest("p1")

            assertTrue(secrets.values.isEmpty())
            assertTrue(local.read("wyr.recoveryRequests.prod") != null)
        }

    @Test
    fun `a count that cannot be read is none`() =
        runTest {
            local.write("wyr.recoveryRequests.dev", "not json")

            assertEquals(0, store(WyrEnvironment.DEV).failedRequests("p1"))
        }

    private fun store(environment: WyrEnvironment) = RecoverySecretStore(secrets, local, environment)
}

private class MapSecretStorage : RecoverySecretStorage {
    val values = mutableMapOf<String, String>()
    val backedUp = mutableListOf<Pair<String, String>>()

    override suspend fun read(key: String): String? = values[key]

    override suspend fun write(
        key: String,
        value: String,
    ) {
        values[key] = value
    }

    override suspend fun clear(key: String) {
        values.remove(key)
    }

    override suspend fun backUp(
        key: String,
        value: String,
    ) {
        backedUp += key to value
    }
}
