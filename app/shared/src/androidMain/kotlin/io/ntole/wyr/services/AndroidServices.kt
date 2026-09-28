package io.ntole.wyr.services

import android.app.Application
import android.util.Log
import com.google.android.gms.games.PlayGamesSdk
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import io.ntole.wyr.core.domain.playgames.PlayGames
import io.ntole.wyr.core.domain.push.DevicePush
import io.ntole.wyr.di.DeviceServices

/**
 * The platform services an Android build has, for [io.ntole.wyr.di.initKoin]: Play Games and
 * Firebase Cloud Messaging where [settings] name their ids, each started here, as their SDKs ask to be
 * as the application is made (their own providers, which would start them on every build, are taken
 * out of the manifest). A service without its ids is off, which the log says once. Called from
 * `WyrApplication.onCreate`.
 */
fun androidDeviceServices(
    application: Application,
    settings: GoogleServiceSettings,
): DeviceServices {
    settings.offLines().forEach { line -> Log.i(LOG_TAG, line) }
    val activities = ActivityTracker().also(application::registerActivityLifecycleCallbacks)
    return DeviceServices(
        playGames = playGamesOf(application, activities, settings),
        push = pushOf(application, activities, settings),
    )
}

/**
 * Firebase started from the build's ids, with no google-services plugin: its own provider, which would
 * start it from a `google-services.json`'s resources, is taken out of the manifest.
 */
private fun pushOf(
    application: Application,
    activities: ActivityTracker,
    settings: GoogleServiceSettings,
): DevicePush {
    if (!settings.pushOn) return DevicePush.None
    val options =
        FirebaseOptions
            .Builder()
            .setProjectId(settings.firebaseProjectId)
            .setApiKey(settings.firebaseApiKey)
            .setGcmSenderId(settings.firebaseSenderId)
            .setApplicationId(settings.firebaseAppId)
            .build()
    FirebaseApp.initializeApp(application, options)
    createDecisionsChannel(application)
    return AndroidPush(application, activities)
}

private fun playGamesOf(
    application: Application,
    activities: ActivityTracker,
    settings: GoogleServiceSettings,
): PlayGames {
    if (!settings.playGamesOn) return PlayGames.None
    PlayGamesSdk.initialize(application)
    return AndroidPlayGames(activities, settings.playGamesServerClientId)
}

internal const val LOG_TAG = "WYR"
