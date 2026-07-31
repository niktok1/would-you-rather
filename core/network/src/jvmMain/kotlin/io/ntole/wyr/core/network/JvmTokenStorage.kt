package io.ntole.wyr.core.network

import java.util.prefs.Preferences

/**
 * Desktop-backed [TokenStorage], using the JVM's own per-user preference store so there is no
 * file path to manage and it works the same on macOS, Windows, and Linux.
 */
public class JvmTokenStorage(
    private val prefs: Preferences = Preferences.userRoot().node("io/ntole/wyr"),
) : TokenStorage {
    override fun read(key: String): String? = prefs.get(key, null)

    override fun write(
        key: String,
        value: String,
    ) {
        prefs.put(key, value)
        prefs.flush()
    }

    override fun remove(key: String) {
        prefs.remove(key)
        prefs.flush()
    }
}
