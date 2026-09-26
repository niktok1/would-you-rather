package io.ntole.wyr.services

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.ntole.wyr.core.domain.push.DevicePush
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * Firebase Cloud Messaging's way into the app (CLAUDE.md §8a, *Push tokens*), declared in the app's
 * manifest. A decision's push carries a notification and data: with the app in the background the
 * system posts it by itself, on the channel the manifest names, and a tap opens the app on the Account
 * screen ([openedFromNotification]); with the app open it arrives here, and posts nothing: the in-app
 * notice reads the player's questions instead, and dots the account icon.
 */
class WyrMessagingService :
    FirebaseMessagingService(),
    KoinComponent {
    override fun onNewToken(token: String) {
        (get<DevicePush>() as? AndroidPush)?.tokenChanged(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        (get<DevicePush>() as? AndroidPush)?.pushed()
    }
}
