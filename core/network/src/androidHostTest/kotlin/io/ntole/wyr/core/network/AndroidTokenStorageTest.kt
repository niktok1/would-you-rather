package io.ntole.wyr.core.network

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How [AndroidTokenStorage] saves a change. A host test has no real `SharedPreferences` (the
 * Android jar here is stubs), so a recording one stands in for it: what is pinned is which of
 * `commit()` and `apply()` is called, on which thread and in what order, not what Android does
 * with it.
 */
class AndroidTokenStorageTest {
    @Test
    fun `a write is committed and never applied`() =
        runTest {
            val prefs = RecordingPreferences()

            storage(prefs).write("k", "v")

            assertEquals(1, prefs.commits)
            assertEquals(0, prefs.applies)
            assertEquals("v", storage(prefs).read("k"))
        }

    @Test
    fun `a removal is committed and never applied`() =
        runTest {
            val prefs = RecordingPreferences(mutableMapOf("k" to "v"))

            storage(prefs).remove("k")

            assertEquals(1, prefs.commits)
            assertEquals(0, prefs.applies)
            assertNull(storage(prefs).read("k"))
        }

    @Test
    fun `the commit runs on the storage dispatcher and not the caller's thread`() =
        runTest {
            // commit() blocks until the file is written, and the app writes a session from the
            // ViewModels' main thread.
            val prefs = RecordingPreferences()
            val executor = Executors.newSingleThreadExecutor { task -> Thread(task, STORAGE_THREAD) }
            try {
                AndroidTokenStorage(prefs, executor.asCoroutineDispatcher()).write("k", "v")
            } finally {
                executor.shutdown()
            }

            assertEquals(listOf(STORAGE_THREAD), prefs.commitThreads)
        }

    @Test
    fun `changes are committed one at a time in the order they were asked for`() =
        runBlocking {
            // Dispatchers.IO alone would start the second commit on another thread while the first
            // still runs, and could finish it first: a clear, say, undone by an older write.
            val firstStarted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val secondStarted = CountDownLatch(1)
            val prefs =
                RecordingPreferences(
                    beforeCommit = { keys ->
                        if ("first" in keys) {
                            firstStarted.countDown()
                            releaseFirst.await(WAIT_SECONDS, TimeUnit.SECONDS)
                        } else {
                            secondStarted.countDown()
                        }
                    },
                )
            val storage = AndroidTokenStorage(prefs)

            val first = launch(Dispatchers.Default) { storage.write("first", "1") }
            assertTrue(firstStarted.await(WAIT_SECONDS, TimeUnit.SECONDS))
            val second = launch(Dispatchers.Default) { storage.write("second", "2") }
            val secondStartedAlongside = secondStarted.await(ALONGSIDE_MILLIS, TimeUnit.MILLISECONDS)
            releaseFirst.countDown()
            joinAll(first, second)

            assertFalse(secondStartedAlongside, "the second commit started while the first still ran")
            assertEquals(listOf(setOf("first"), setOf("second")), prefs.committedKeys)
        }

    @Test
    fun `a commit that fails to reach the disk is reported`() =
        runTest {
            val prefs = RecordingPreferences(commitSucceeds = false)

            assertFailsWith<IOException> { storage(prefs).write("k", "v") }
        }

    @Test
    fun `a write made as its caller is cancelled still lands`() =
        runTest {
            // What happens when the caller is cancelled between the server's answer and the write:
            // the session the server just rotated in must be stored all the same.
            val prefs = RecordingPreferences()
            val storage = storage(prefs)

            launch {
                cancel()
                storage.write("k", "v")
            }.join()

            assertEquals("v", prefs.values["k"])
        }

    private fun TestScope.storage(prefs: SharedPreferences) =
        AndroidTokenStorage(prefs, StandardTestDispatcher(testScheduler))

    private companion object {
        const val STORAGE_THREAD = "wyr-storage-test"
        const val WAIT_SECONDS = 5L

        /** Ample for a free IO thread to pick the second commit up, were one allowed to. */
        const val ALONGSIDE_MILLIS = 500L
    }
}

/** `SharedPreferences` in memory, recording how each change was saved and on which thread. */
private class RecordingPreferences(
    val values: MutableMap<String, String> = mutableMapOf(),
    private val commitSucceeds: Boolean = true,
    private val beforeCommit: (keys: Set<String>) -> Unit = {},
) : SharedPreferences {
    var commits = 0
    var applies = 0
    val commitThreads: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val committedKeys: MutableList<Set<String>> = Collections.synchronizedList(mutableListOf())

    override fun getString(
        key: String?,
        defValue: String?,
    ): String? = values[key] ?: defValue

    override fun contains(key: String?): Boolean = key in values

    override fun getAll(): MutableMap<String, *> = values

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun getStringSet(
        key: String?,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? = unused()

    override fun getInt(
        key: String?,
        defValue: Int,
    ): Int = unused()

    override fun getLong(
        key: String?,
        defValue: Long,
    ): Long = unused()

    override fun getFloat(
        key: String?,
        defValue: Float,
    ): Float = unused()

    override fun getBoolean(
        key: String?,
        defValue: Boolean,
    ): Boolean = unused()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ): Unit = unused()

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ): Unit = unused()

    private inner class Editor : SharedPreferences.Editor {
        private val puts = mutableMapOf<String, String>()
        private val removals = mutableSetOf<String>()

        override fun putString(
            key: String,
            value: String?,
        ): SharedPreferences.Editor {
            if (value == null) removals += key else puts[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            removals += key
            return this
        }

        override fun commit(): Boolean {
            val keys = puts.keys + removals
            beforeCommit(keys)
            commits++
            commitThreads += Thread.currentThread().name
            committedKeys += keys
            save()
            return commitSucceeds
        }

        override fun apply() {
            applies++
            save()
        }

        private fun save() {
            removals.forEach(values::remove)
            values.putAll(puts)
        }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = unused()

        override fun putInt(
            key: String?,
            value: Int,
        ): SharedPreferences.Editor = unused()

        override fun putLong(
            key: String?,
            value: Long,
        ): SharedPreferences.Editor = unused()

        override fun putFloat(
            key: String?,
            value: Float,
        ): SharedPreferences.Editor = unused()

        override fun putBoolean(
            key: String?,
            value: Boolean,
        ): SharedPreferences.Editor = unused()

        override fun clear(): SharedPreferences.Editor = unused()
    }

    private fun unused(): Nothing = throw UnsupportedOperationException("AndroidTokenStorage stores strings only")
}
