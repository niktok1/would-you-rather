package io.ntole.wyr.services

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.firebase.messaging.FirebaseMessaging
import io.ntole.wyr.core.domain.push.DevicePush
import io.ntole.wyr.core.domain.push.PushPlatform
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Firebase Cloud Messaging on this device (CLAUDE.md §8a, *Push tokens*), made only once Firebase has
 * started with the build's ids. [WyrMessagingService] tells it of a new token and of a push that
 * arrived while the app is open, which shows no notification of its own: the in-app notice reads it.
 */
internal class AndroidPush(
    private val application: Application,
    private val activities: ActivityTracker,
) : DevicePush {
    override val available: Boolean = true
    override val platform: PushPlatform = PushPlatform.ANDROID

    private val tokens =
        MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val pushes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val newTokens: Flow<String> = tokens.asSharedFlow()
    override val received: Flow<Unit> = pushes.asSharedFlow()

    // Deprecated since firebase-messaging 25.1 for registering by installation id (`register`,
    // `onRegistered`), which the server's FCM HTTP v1 sends do not address yet: they name a registration
    // token, which Google says keeps working meanwhile (CLAUDE.md §8a, Push tokens).
    @Suppress("DEPRECATION")
    override suspend fun token(): String? =
        FirebaseMessaging
            .getInstance()
            .token
            .resultOrNull()
            ?.takeIf { it.isNotBlank() }

    /** Firebase gave this device a new token. */
    fun tokenChanged(token: String) {
        tokens.tryEmit(token)
    }

    /** A push arrived while the app is open. */
    fun pushed() {
        pushes.tryEmit(Unit)
    }

    /**
     * Asks for `POST_NOTIFICATIONS` on Android 13 and later, once, ever, from the activity on screen;
     * before 13 notifications need no asking. Kept as asked only once it was asked, so a moment with no
     * activity on screen asks at the next one.
     */
    override fun askPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val kept = application.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (kept.getBoolean(ASKED, false)) return
        val granted =
            application.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            val activity = activities.current() ?: return
            activity.runOnUiThread { activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0) }
        }
        kept.edit().putBoolean(ASKED, true).apply()
    }

    private companion object {
        /** A device's own, which may go in a backup, unlike the session's (`wyr.auth.xml`). */
        const val PREFERENCES = "wyr.device"
        const val ASKED = "notifications_asked"
    }
}
