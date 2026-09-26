package io.ntole.wyr.services

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * What a Google Play services task ends in, or null when it failed or was cancelled: Google's own
 * failure is the platform's, and a port answers it as nothing done. The caller's cancellation still
 * throws, as it must.
 */
internal suspend fun <T> Task<T>.resultOrNull(): T? =
    suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task -> continuation.resume(if (task.isSuccessful) task.result else null) }
    }
