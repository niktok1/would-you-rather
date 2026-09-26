package io.ntole.wyr

import android.app.Application
import io.ntole.wyr.analytics.AnalyticsSettings
import io.ntole.wyr.di.initKoin
import io.ntole.wyr.services.GoogleServiceSettings
import io.ntole.wyr.services.androidDeviceServices
import org.koin.android.ext.koin.androidContext

/**
 * Exists solely to start DI with an Android `Context`, the environment this build's flavor was made
 * for, the analytics project the build names, and the Google services it has ids for (CLAUDE.md §8a).
 *
 * Those are what shared code cannot obtain for itself: the context is the token storage's, and
 * `BuildConfig` is generated in this module, one per flavor (CLAUDE.md §8e), with the PostHog key and
 * host from the build's `wyr.posthog.*` settings, the app's version (§8g), and its build number, the
 * `versionCode`, which every request names (§8b, *Minimum client version*). That is exactly the kind of
 * thing a platform entry point is for (CLAUDE.md §3).
 */
class WyrApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin(
            environmentName = BuildConfig.WYR_ENV,
            analytics =
                AnalyticsSettings(
                    key = BuildConfig.POSTHOG_KEY,
                    host = BuildConfig.POSTHOG_HOST,
                    appVersion = BuildConfig.VERSION_NAME,
                ),
            build = BuildConfig.VERSION_CODE,
            device =
                androidDeviceServices(
                    application = this,
                    settings =
                        GoogleServiceSettings(
                            playGamesAppId = BuildConfig.PLAY_GAMES_APP_ID,
                            playGamesServerClientId = BuildConfig.PLAY_GAMES_SERVER_CLIENT_ID,
                            firebaseProjectId = BuildConfig.FIREBASE_PROJECT_ID,
                            firebaseApiKey = BuildConfig.FIREBASE_API_KEY,
                            firebaseSenderId = BuildConfig.FIREBASE_SENDER_ID,
                            firebaseAppId = BuildConfig.FIREBASE_APP_ID,
                        ),
                ),
        ) {
            androidContext(this@WyrApplication)
        }
    }
}
