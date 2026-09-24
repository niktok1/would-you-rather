package io.ntole.wyr

import android.app.Application
import io.ntole.wyr.di.initKoin
import org.koin.android.ext.koin.androidContext

/**
 * Exists solely to start DI with an Android `Context` and the environment this build's flavor was
 * made for.
 *
 * Those are the two things shared code cannot obtain for itself: the context is the token storage's,
 * and `BuildConfig` is generated in this module, one per flavor (CLAUDE.md §8e). That is exactly the
 * kind of thing a platform entry point is for (CLAUDE.md §3).
 */
class WyrApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin(environmentName = BuildConfig.WYR_ENV) {
            androidContext(this@WyrApplication)
        }
    }
}
