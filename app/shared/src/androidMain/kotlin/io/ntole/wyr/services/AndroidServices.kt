package io.ntole.wyr.services

import android.app.Application
import android.util.Log
import com.google.android.gms.games.PlayGamesSdk
import io.ntole.wyr.core.domain.playgames.PlayGames
import io.ntole.wyr.di.DeviceServices

/**
 * The platform services an Android build has, for [io.ntole.wyr.di.initKoin]: Play Games where
 * [settings] name its ids, started here, as its SDK asks to be as the application is made (its own
 * provider, which would start it on every build, is taken out of the manifest). A service without its
 * ids is off, which the log says once. Called from `WyrApplication.onCreate`.
 */
fun androidDeviceServices(
    application: Application,
    settings: GoogleServiceSettings,
): DeviceServices {
    settings.offLines().forEach { line -> Log.i(LOG_TAG, line) }
    val activities = ActivityTracker(application)
    return DeviceServices(playGames = playGamesOf(application, activities, settings))
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
