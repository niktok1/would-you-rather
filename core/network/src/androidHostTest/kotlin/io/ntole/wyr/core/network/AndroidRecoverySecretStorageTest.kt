package io.ntole.wyr.core.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What [AndroidRecoverySecretStorage] asks of Block Store (CLAUDE.md §8a, *Recovery*). A host test has
 * no Play services, so a recording [BlockStore] stands in for it: what is pinned is what is stored
 * under which key, and when the cloud backup may have it, not what Play services does with that. The
 * calls to Play services themselves (`PlayServicesBlockStore`) run only on a phone.
 */
class AndroidRecoverySecretStorageTest {
    private val blockStore = RecordingBlockStore()
    private val storage = AndroidRecoverySecretStorage(blockStore)

    @Test
    fun `a secret is stored under its key and read back as it was`() =
        runTest {
            storage.write("wyr.recovery.dev", "secret")

            assertEquals("secret", storage.read("wyr.recovery.dev"))
            assertEquals(listOf("wyr.recovery.dev"), blockStore.stored.map { it.key })
        }

    @Test
    fun `nothing stored under a key reads as none`() =
        runTest {
            assertNull(storage.read("wyr.recovery.dev"))
        }

    /** Decision A5 (CLAUDE.md §8b, *Provider linking*): never in a backup Google could read. */
    @Test
    fun `a secret goes to the cloud backup only while the backup is encrypted end to end`() =
        runTest {
            blockStore.endToEndEncrypted = { true }
            storage.write("k", "encrypted")
            blockStore.endToEndEncrypted = { false }
            storage.write("k", "not encrypted")

            assertEquals(listOf(true, false), blockStore.stored.map { it.backUpToCloud })
        }

    @Test
    fun `a phone that cannot say whether its backup is encrypted keeps the secret out of it`() =
        runTest {
            blockStore.endToEndEncrypted = { throw IOException("Play services unavailable") }

            storage.write("k", "secret")

            assertEquals(listOf(false), blockStore.stored.map { it.backUpToCloud })
            assertEquals("secret", storage.read("k"))
        }

    /** None stored makes the data layer ask for a secret, so a failed read must never look like it. */
    @Test
    fun `a Block Store that fails throws rather than answer none`() =
        runTest {
            blockStore.failure = IOException("no Play services")

            assertFailsWith<IOException> { storage.read("k") }
            assertFailsWith<IOException> { storage.write("k", "secret") }
            assertFailsWith<IOException> { storage.clear("k") }
        }

    @Test
    fun `a clear deletes the key`() =
        runTest {
            storage.write("k", "secret")

            storage.clear("k")

            assertNull(storage.read("k"))
            assertEquals(listOf("k"), blockStore.deleted)
        }

    @Test
    fun `a write is made whole though its caller is cancelled while the encryption is checked`() =
        runTest {
            val answer = CompletableDeferred<Boolean>()
            blockStore.endToEndEncrypted = { answer.await() }

            val writer = launch { storage.write("k", "secret") }
            testScheduler.advanceUntilIdle()
            writer.cancel()
            answer.complete(true)
            writer.join()

            assertTrue(writer.isCancelled)
            assertEquals("secret", storage.read("k"))
        }
}

/** Keeps what it is given in memory, and records every store and delete it is asked for. */
private class RecordingBlockStore : BlockStore {
    class Stored(
        val key: String,
        val backUpToCloud: Boolean,
    )

    private val entries = mutableMapOf<String, ByteArray>()
    val stored = mutableListOf<Stored>()
    val deleted = mutableListOf<String>()
    var endToEndEncrypted: suspend () -> Boolean = { true }

    /** When set, every call but the encryption check throws it. */
    var failure: Exception? = null

    override suspend fun isEndToEndEncryptionAvailable(): Boolean = endToEndEncrypted()

    override suspend fun store(
        key: String,
        bytes: ByteArray,
        backUpToCloud: Boolean,
    ) {
        failure?.let { throw it }
        stored += Stored(key, backUpToCloud)
        entries[key] = bytes
    }

    override suspend fun retrieve(key: String): ByteArray? {
        failure?.let { throw it }
        return entries[key]
    }

    override suspend fun delete(key: String) {
        failure?.let { throw it }
        deleted += key
        entries.remove(key)
    }
}
