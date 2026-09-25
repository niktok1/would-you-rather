package io.ntole.wyr.core.data

import io.ntole.wyr.core.network.RecoverySecretStorage
import kotlinx.io.IOException

/** A secret store that keeps values in memory, and fails whichever kind of call it is told to. */
internal class FlakySecretStorage : RecoverySecretStorage {
    private val values = mutableMapOf<String, String>()
    var readFails = false
    var writeFails = false
    var clearFails = false

    /** Every value stored again by [backUp], which fails whenever a write would. */
    val backedUp = mutableListOf<String>()

    override suspend fun read(key: String): String? {
        if (readFails) throw IOException("the secret store is not available")
        return values[key]
    }

    override suspend fun write(
        key: String,
        value: String,
    ) {
        if (writeFails) throw IOException("the secret store is not available")
        values[key] = value
    }

    override suspend fun clear(key: String) {
        if (clearFails) throw IOException("the secret store is not available")
        values.remove(key)
    }

    override suspend fun backUp(
        key: String,
        value: String,
    ) {
        if (writeFails) throw IOException("the secret store is not available")
        backedUp += value
    }
}
