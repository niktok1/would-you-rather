package io.ntole.wyr.core.network

import platform.Foundation.NSUserDefaults

/**
 * iOS-backed [TokenStorage].
 *
 * `NSUserDefaults`, not the Keychain — see the security note on [TokenStorage]. Worth knowing:
 * defaults are included in device backups, so a restored backup restores the guest session too.
 */
public class IosTokenStorage(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : TokenStorage {
    override fun read(key: String): String? = defaults.stringForKey(key)

    override fun write(
        key: String,
        value: String,
    ) {
        defaults.setObject(value, key)
    }

    override fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }
}
