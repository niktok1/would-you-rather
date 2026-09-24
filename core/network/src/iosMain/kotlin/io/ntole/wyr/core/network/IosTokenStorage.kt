package io.ntole.wyr.core.network

import platform.Foundation.NSUserDefaults

/**
 * iOS-backed [TokenStorage].
 *
 * `NSUserDefaults`, not the Keychain — see the security note on [TokenStorage]. Worth knowing:
 * defaults are included in device backups, so a restored backup restores the guest session too.
 *
 * Whether a change survives the app being killed the moment [write] returns, as [TokenStorage.write]
 * requires, is up to `NSUserDefaults`, and nobody has checked it on a device (CLAUDE.md §9).
 */
public class IosTokenStorage(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : TokenStorage {
    override fun read(key: String): String? = defaults.stringForKey(key)

    override suspend fun write(
        key: String,
        value: String,
    ) {
        defaults.setObject(value, key)
    }

    override suspend fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }
}
