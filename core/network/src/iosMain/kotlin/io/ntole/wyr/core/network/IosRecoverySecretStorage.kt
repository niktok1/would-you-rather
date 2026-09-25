package io.ntole.wyr.core.network

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecAttrSynchronizable
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * iOS's [RecoverySecretStorage]: one generic-password item in the Keychain per key, under [service].
 *
 * Synchronizable (`kSecAttrSynchronizable`), so iCloud Keychain carries it to every iPhone signed in
 * to the same Apple account, where each recovers the same player in a session of its own (CLAUDE.md
 * §8a, *Recovery*). The Keychain also keeps it when the app is deleted and installed again, and a
 * phone restored from a backup gets it through iCloud Keychain once that syncs. It is readable once
 * the phone has been unlocked after starting (`kSecAttrAccessibleAfterFirstUnlock`), and must not be
 * one of the classes that end in `ThisDeviceOnly`, which never sync.
 *
 * Each call runs on the caller's thread, as `IosTokenStorage`'s do, and has no suspension point, so
 * a change asked for is made whole whatever becomes of the caller. A read or a clear that finds no
 * item is no failure; any other status but success throws. None of it has run outside the compiler
 * (CLAUDE.md §9): the test binary is no signed app, so the app on a phone is the first to call it.
 */
@OptIn(ExperimentalForeignApi::class)
public class IosRecoverySecretStorage(
    private val service: String = SERVICE,
) : RecoverySecretStorage {
    override suspend fun read(key: String): String? =
        keychain {
            val result = scope.alloc<CFTypeRefVar>()
            val query = itemOf(key) + (kSecReturnData to kCFBooleanTrue) + (kSecMatchLimit to kSecMatchLimitOne)
            when (val status = SecItemCopyMatching(dictionary(query), result.ptr)) {
                errSecSuccess -> (CFBridgingRelease(result.value) as? NSData)?.toByteArray()?.decodeToString()
                errSecItemNotFound -> null
                else -> failed("read", status)
            }
        }

    override suspend fun write(
        key: String,
        value: String,
    ): Unit =
        keychain {
            val data = retained(value.encodeToByteArray().toNSData())
            val updated = SecItemUpdate(dictionary(itemOf(key)), dictionary(listOf(kSecValueData to data)))
            if (updated == errSecItemNotFound) {
                val attributes =
                    itemOf(key) + (kSecValueData to data) + (kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlock)
                val added = SecItemAdd(dictionary(attributes), null)
                if (added != errSecSuccess) failed("add", added)
            } else if (updated != errSecSuccess) {
                failed("update", updated)
            }
        }

    override suspend fun clear(key: String): Unit =
        keychain {
            val status = SecItemDelete(dictionary(itemOf(key)))
            if (status != errSecSuccess && status != errSecItemNotFound) failed("delete", status)
        }

    /** What names the one item for [key], and only a synchronizable one. */
    private fun Keychain.itemOf(key: String): List<Pair<CFStringRef?, CFTypeRef?>> =
        listOf(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to retained(service),
            kSecAttrAccount to retained(key),
            kSecAttrSynchronizable to kCFBooleanTrue,
        )

    private fun failed(
        call: String,
        status: Int,
    ): Nothing = throw IllegalStateException("Keychain $call failed with OSStatus $status")

    public companion object {
        /** The Keychain service every environment's item is kept under, each with its key as the account. */
        public const val SERVICE: String = "io.ntole.wyr.recovery"
    }
}

/**
 * The Core Foundation objects one Keychain call makes, released together once it is done: the values
 * bridged from Kotlin, and the dictionaries holding them, which retain what they hold themselves.
 */
@OptIn(ExperimentalForeignApi::class)
private class Keychain(
    val scope: MemScope,
) {
    private val made = mutableListOf<CFTypeRef?>()

    fun retained(value: Any): CFTypeRef? = CFBridgingRetain(value).also { made += it }

    fun dictionary(entries: List<Pair<CFStringRef?, CFTypeRef?>>): CFDictionaryRef? {
        val keys = scope.allocArrayOf(entries.map { it.first })
        val values = scope.allocArrayOf(entries.map { it.second })
        return CFDictionaryCreate(
            kCFAllocatorDefault,
            keys.reinterpret(),
            values.reinterpret(),
            entries.size.convert(),
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        ).also { made += it }
    }

    fun releaseAll() {
        made.forEach { if (it != null) CFRelease(it) }
    }
}

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> keychain(block: Keychain.() -> T): T =
    memScoped {
        val keychain = Keychain(this)
        try {
            keychain.block()
        } finally {
            keychain.releaseAll()
        }
    }

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.convert()) }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray = bytes?.readBytes(length.convert()) ?: ByteArray(0)
