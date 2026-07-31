package io.ntole.wyr.core.network

/**
 * Minimal persistent key-value storage for credentials.
 *
 * Deliberately tiny so each platform can back it with whatever it already has. The concrete
 * implementations live in this module's platform source sets and are wired up by DI in
 * `:app:shared`, which is what keeps the Android `Context` requirement out of common code.
 *
 * Security note: today the platform implementations use ordinary preference storage, not the
 * Keychain / EncryptedSharedPreferences. That is acceptable while the only credential is a
 * guest refresh token, and must be revisited before real accounts exist.
 */
public interface TokenStorage {
    public fun read(key: String): String?

    public fun write(
        key: String,
        value: String,
    )

    public fun remove(key: String)
}

/** Non-persistent fallback. Used by tests, and by any platform without storage wired up yet. */
public class InMemoryTokenStorage : TokenStorage {
    private val values = mutableMapOf<String, String>()

    override fun read(key: String): String? = values[key]

    override fun write(
        key: String,
        value: String,
    ) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}
