package io.ntole.wyr.core.network

import android.content.Context

/**
 * Android-backed [TokenStorage].
 *
 * Plain `SharedPreferences`, not `EncryptedSharedPreferences` — see the security note on
 * [TokenStorage].
 */
public class AndroidTokenStorage(
    context: Context,
) : TokenStorage {
    private val prefs = context.getSharedPreferences("wyr.auth", Context.MODE_PRIVATE)

    override fun read(key: String): String? = prefs.getString(key, null)

    override fun write(
        key: String,
        value: String,
    ) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}
