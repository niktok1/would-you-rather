package io.ntole.wyr.core.network

import android.content.SharedPreferences
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * How [AndroidTokenStorage] saves a change. A host test has no real `SharedPreferences` (the
 * Android jar here is stubs), so a recording one stands in for it: what is pinned is which of
 * `commit()` and `apply()` is called, and on which thread, not what Android does with it.
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
    }
}

/** `SharedPreferences` in memory, recording how each change was saved and on which thread. */
private class RecordingPreferences(
    val values: MutableMap<String, String> = mutableMapOf(),
    private val commitSucceeds: Boolean = true,
) : SharedPreferences {
    var commits = 0
    var applies = 0
    val commitThreads = mutableListOf<String>()

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
            commits++
            commitThreads += Thread.currentThread().name
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
