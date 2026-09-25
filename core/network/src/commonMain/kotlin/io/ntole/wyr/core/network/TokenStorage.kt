package io.ntole.wyr.core.network

/**
 * Minimal persistent key-value storage for credentials.
 *
 * Deliberately tiny so each platform can back it with whatever it already has. The concrete
 * implementations live in this module's platform source sets and are wired up by DI in
 * `:app:shared`, which is what keeps the Android `Context` requirement out of common code.
 *
 * Security note: the platform implementations use ordinary preference storage, not the Keychain or
 * EncryptedSharedPreferences. That is enough for a game that stores nothing personal (CLAUDE.md §8a):
 * the one credential kept is the session's refresh token, a registered player's included, and no
 * password is ever stored.
 */
public interface TokenStorage {
    public fun read(key: String): String?

    /**
     * Stores [value] under [key], and returns only once it would survive the app's process being
     * killed. Refresh tokens rotate on every use (CLAUDE.md §8a): once the server has answered a
     * refresh, the token it answered with is the one sure to work. The one the app sent works once
     * more at most, and not at all if it was already the previous one, so losing the new one to a
     * kill can orphan the guest.
     *
     * Suspending, so that an implementation whose durable write blocks can make it off the
     * caller's thread, which in the app is often the main one. A write asked for is made whole
     * even if the caller is cancelled meanwhile, as a write that did not suspend would be.
     */
    public suspend fun write(
        key: String,
        value: String,
    )

    /** Removes [key], as durably as [write] stores one. */
    public suspend fun remove(key: String)
}

/** Non-persistent fallback. Used by tests, and by any platform without storage wired up yet. */
public class InMemoryTokenStorage : TokenStorage {
    private val values = mutableMapOf<String, String>()

    override fun read(key: String): String? = values[key]

    override suspend fun write(
        key: String,
        value: String,
    ) {
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }
}
