package io.ntole.wyr.core.network

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Android's [RecoverySecretStorage]: Google Play services' Block Store (CLAUDE.md §2, §8a).
 *
 * Block Store keeps its entries outside the app's own data. They outlive the app being uninstalled
 * and installed again on this phone, and move to a new phone set up from this one by device-to-device
 * transfer. They go to the cloud backup as well only when asked, and this asks only while the backup
 * is end-to-end encrypted (a screen lock set, among other things), so the secret never sits in a
 * backup Google could read; otherwise a phone restored from the cloud recovers nobody, and a new
 * guest is minted there.
 *
 * On a phone without Play services, or with one too old for Block Store, every call throws, and the
 * data layer carries on as it does with no secret.
 */
public class AndroidRecoverySecretStorage internal constructor(
    private val blockStore: BlockStore,
) : RecoverySecretStorage {
    public constructor(context: Context) : this(PlayServicesBlockStore(context))

    override suspend fun read(key: String): String? = blockStore.retrieve(key)?.decodeToString()

    // NonCancellable, as TokenStorage's writes are: the encryption check suspends before the store
    // is even asked, and a cancellation there would drop a write that was asked for.
    override suspend fun write(
        key: String,
        value: String,
    ): Unit =
        withContext(NonCancellable) {
            blockStore.store(key, value.encodeToByteArray(), backUpToCloud = backupIsEndToEndEncrypted())
        }

    override suspend fun clear(key: String): Unit = withContext(NonCancellable) { blockStore.delete(key) }

    /**
     * Whether to let the secret into the cloud backup. A phone that cannot say is taken as one whose
     * backup is not encrypted end to end: the secret then stays on this phone, and still moves by
     * transfer.
     */
    private suspend fun backupIsEndToEndEncrypted(): Boolean =
        try {
            blockStore.isEndToEndEncryptionAvailable()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unknown: Exception) {
            false
        }
}
