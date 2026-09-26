package io.ntole.wyr

import android.app.Application
import io.ntole.wyr.analytics.AnalyticsSettings
import io.ntole.wyr.di.initKoin
import org.koin.android.ext.koin.androidContext

/**
 * Exists solely to start DI with an Android `Context`, the environment this build's flavor was made
 * for, and the analytics project the build names.
 *
 * Those are what shared code cannot obtain for itself: the context is the token storage's, and
 * `BuildConfig` is generated in this module, one per flavor (CLAUDE.md §8e), with the PostHog key and
 * host from the build's `wyr.posthog.*` settings and the app's version (§8g). That is exactly the kind
 * of thing a platform entry point is for (CLAUDE.md §3).
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
        ) {
            androidContext(this@WyrApplication)
        }
    }
}
