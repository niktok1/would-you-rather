package io.ntole.wyr.services

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import io.ntole.wyr.language.SerbianCyrillicStrings
import org.koin.mp.KoinPlatform

/**
 * The notifications' channel, a moderator's decisions its one kind (CLAUDE.md §8a, *Push tokens*): its
 * id is the manifest's `default_notification_channel_id`, on which the system posts a push that arrives
 * with the app in the background. Named in Serbian Cyrillic, as the push's own text is.
 */
internal const val DECISIONS_CHANNEL = "decisions"

/** Makes the channel, or names it again, which Android allows: from Android 8, which has channels. */
internal fun createDecisionsChannel(application: Application) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel =
        NotificationChannel(
            DECISIONS_CHANNEL,
            SerbianCyrillicStrings.notice.channel,
            NotificationManager.IMPORTANCE_DEFAULT,
        )
    application.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
}

/**
 * What the server's decision push names in its data (`DecisionNotifier`: `type`), which the system
 * puts in the extras of the intent a tap on its notification opens the app with.
 */
private const val SUBMISSION_DECIDED = "submission_decided"

/**
 * Called by the activity with the intent it was opened with, or given later: a tap on a decision's
 * notification opens the Account screen, where My questions shows it. Any other intent, a launcher's,
 * does nothing. Called once per intent, never for a rotation's.
 */
fun openedFromNotification(intent: Intent?) {
    if (intent?.getStringExtra("type") != SUBMISSION_DECIDED) return
    // Handled once: an intent kept for the activity must not open the screen again.
    intent.removeExtra("type")
    KoinPlatform.getKoin().get<AppServices>().notificationOpened()
}
