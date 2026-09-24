package io.ntole.wyr.core.network

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Android-backed [TokenStorage].
 *
 * Plain `SharedPreferences`, not `EncryptedSharedPreferences` — see the security note on
 * [TokenStorage].
 *
 * Every change is `commit()`ed, never `apply()`ed. `apply()` returns before the file is written,
 * so a process killed just after a refresh (swiped away, or by the low-memory killer) could start
 * again holding the refresh token the server had just rotated out, and the next 401 would cost the
 * player their guest account. `commit()` returns once the file is written, and blocks until then,
 * so it runs on [ioDispatcher]: a session is written from the calling coroutine, which for the
 * ViewModels is the main thread.
 *
 * [ioDispatcher] runs one change at a time, in the order they were asked for. `Dispatchers.IO`
 * alone could commit a clear and a refreshed session the other way round, which the synchronous
 * `apply()` on the calling thread never did.
 */
public class AndroidTokenStorage internal constructor(
    private val prefs: SharedPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1, "wyr-token-storage"),
) : TokenStorage {
    public constructor(context: Context) : this(context.getSharedPreferences("wyr.auth", Context.MODE_PRIVATE))

    override fun read(key: String): String? = prefs.getString(key, null)

    override suspend fun write(
        key: String,
        value: String,
    ): Unit = commit { editor -> editor.putString(key, value) }

    override suspend fun remove(key: String): Unit = commit { editor -> editor.remove(key) }

    private suspend fun commit(change: (SharedPreferences.Editor) -> Unit) {
        // NonCancellable: a change asked for is made whole, as TokenStorage promises, rather than
        // dropped because the caller was cancelled before the dispatch.
        val written =
            withContext(ioDispatcher + NonCancellable) {
                prefs.edit().also(change).commit()
            }
        // commit() reports a failed write, which apply() never did. The change is already in
        // memory, so this process still reads it; it is the next start that would not have it. The
        // caller hears of it all the same, and the data layer reports it as NETWORK.
        if (!written) throw IOException("SharedPreferences could not write the change to disk")
    }
}
