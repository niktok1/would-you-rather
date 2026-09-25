package io.ntole.wyr.core.network

import io.ntole.wyr.core.network.environment.WyrEnvironment
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The recovery secret for [environment]'s server as this device keeps it (CLAUDE.md §8a,
 * *Recovery*): the secret in the platform's [secrets], and beside the session in [local], how often
 * this install has asked the server for one in vain.
 *
 * Each environment's secret is kept under a key of its own ([secretKeyFor]), as its session is
 * ([SessionStore]): each server's database holds its own secrets (CLAUDE.md §8e), so a DEV secret sent
 * to PROD recovers nobody, and PROD would answer that it is unknown, which drops it. On iOS the
 * environments' builds share one Keychain, so the key is what keeps them apart.
 *
 * The count of failed requests stays in [local], so it goes where the session goes and never with
 * the secret, and a reinstall, which empties [local], starts it again.
 */
public class RecoverySecretStore(
    private val secrets: RecoverySecretStorage,
    private val local: TokenStorage,
    environment: WyrEnvironment,
    private val json: Json = WyrJson,
) {
    private val secretKey = secretKeyFor(environment)
    private val failedRequestsKey = failedRequestsKeyFor(environment)

    /** The secret kept for this server, or null when there is none. Throws when it cannot be read. */
    public suspend fun read(): String? = secrets.read(secretKey)

    /** Keeps [secret] for this server, in place of any before it. Throws when it cannot. */
    public suspend fun write(secret: String) {
        secrets.write(secretKey, secret)
    }

    /** Drops this server's secret. Throws when it cannot. */
    public suspend fun clear() {
        secrets.clear(secretKey)
    }

    /**
     * How many times this install has asked the server for a secret for [playerId] and not come away
     * with one kept. Counted for one player at a time, so another player's count reads as none.
     */
    public fun failedRequests(playerId: String): Int {
        val raw = local.read(failedRequestsKey) ?: return 0
        val failed = runCatching { json.decodeFromString<FailedRequests>(raw) }.getOrNull() ?: return 0
        return if (failed.playerId == playerId) failed.count else 0
    }

    /** Counts one more failed request for [playerId], which replaces the count of any other player. */
    public suspend fun countFailedRequest(playerId: String) {
        val count = failedRequests(playerId) + 1
        local.write(failedRequestsKey, json.encodeToString(FailedRequests(playerId, count)))
    }

    /** Forgets every failed request, as a reinstall does. */
    public suspend fun clearFailedRequests() {
        local.remove(failedRequestsKey)
    }

    @Serializable
    private data class FailedRequests(
        val playerId: String,
        val count: Int,
    )

    internal companion object {
        /**
         * The key [environment]'s secret is kept under: Block Store's entry on Android, the Keychain
         * item's account on iOS. Renaming one strands every secret kept under the old name.
         */
        fun secretKeyFor(environment: WyrEnvironment): String =
            when (environment) {
                WyrEnvironment.LOCAL -> "wyr.recovery.local"
                WyrEnvironment.DEV -> "wyr.recovery.dev"
                WyrEnvironment.PROD -> "wyr.recovery.prod"
            }

        fun failedRequestsKeyFor(environment: WyrEnvironment): String =
            when (environment) {
                WyrEnvironment.LOCAL -> "wyr.recoveryRequests.local"
                WyrEnvironment.DEV -> "wyr.recoveryRequests.dev"
                WyrEnvironment.PROD -> "wyr.recoveryRequests.prod"
            }
    }
}
