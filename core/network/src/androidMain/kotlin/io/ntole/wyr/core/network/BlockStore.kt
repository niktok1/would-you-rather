package io.ntole.wyr.core.network

import android.content.Context
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.BlockstoreClient
import com.google.android.gms.auth.blockstore.DeleteBytesRequest
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The few calls of Google Play services' Block Store that [AndroidRecoverySecretStorage] makes, as
 * suspending functions, so its choices can be tested without Play services, which a host test has
 * none of. Each throws what Play services fails with.
 */
internal interface BlockStore {
    /** Whether a backup of this phone to the cloud is end-to-end encrypted, as with a screen lock set. */
    suspend fun isEndToEndEncryptionAvailable(): Boolean

    suspend fun store(
        key: String,
        bytes: ByteArray,
        backUpToCloud: Boolean,
    )

    /** The bytes stored under [key], or null when none are. */
    suspend fun retrieve(key: String): ByteArray?

    suspend fun delete(key: String)
}

/**
 * [BlockStore] as Play services serves it. Nothing but the calls themselves: what is stored, and
 * whether it is backed up, is [AndroidRecoverySecretStorage]'s to decide.
 *
 * The client is made at the first call, not before, so a phone without Play services fails there,
 * where the caller is ready for it, rather than when the app wires itself up.
 */
internal class PlayServicesBlockStore(
    context: Context,
) : BlockStore {
    private val client: BlockstoreClient by lazy { Blockstore.getClient(context.applicationContext) }

    override suspend fun isEndToEndEncryptionAvailable(): Boolean = client.isEndToEndEncryptionAvailable().await()

    override suspend fun store(
        key: String,
        bytes: ByteArray,
        backUpToCloud: Boolean,
    ) {
        val data =
            StoreBytesData
                .Builder()
                .setKey(key)
                .setBytes(bytes)
                .setShouldBackupToCloud(backUpToCloud)
                .build()
        val stored = client.storeBytes(data).await()
        // It answers with how many bytes it kept.
        if (stored != bytes.size) throw IOException("Block Store kept $stored of ${bytes.size} bytes")
    }

    override suspend fun retrieve(key: String): ByteArray? {
        val request = RetrieveBytesRequest.Builder().setKeys(listOf(key)).build()
        val response = client.retrieveBytes(request).await()
        return response.blockstoreDataMap[key]?.bytes
    }

    override suspend fun delete(key: String) {
        // False when nothing was stored under it, which is what was asked for all the same.
        client.deleteBytes(DeleteBytesRequest.Builder().setKeys(listOf(key)).build()).await()
    }
}

/**
 * The task's result once it completes. Not kotlinx-coroutines-play-services' `await`, which would be
 * a dependency of its own (CLAUDE.md §2) for these few lines. The listener runs on whichever thread
 * completes the task rather than the main one Play services would pick by default, and only resumes
 * the caller, on the caller's own dispatcher. Cancelling the caller leaves the task running: a
 * change Play services was asked for is made all the same.
 */
private suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnCompleteListener(OnCompletingThread) { task ->
            val failure = task.exception
            when {
                failure != null -> continuation.resumeWithException(failure)
                task.isCanceled -> continuation.cancel()
                else -> continuation.resume(task.result)
            }
        }
    }

private val OnCompletingThread = Executor { command -> command.run() }
