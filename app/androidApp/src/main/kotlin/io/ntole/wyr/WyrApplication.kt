package io.ntole.wyr

import android.app.Application
import io.ntole.wyr.di.initKoin
import org.koin.android.ext.koin.androidContext

/**
 * Exists solely to start DI with an Android `Context`.
 *
 * That context is the one thing the shared token storage cannot obtain for itself, which is
 * exactly the kind of thing a platform entry point is for (CLAUDE.md §3).
 */
class WyrApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin {
            androidContext(this@WyrApplication)
        }
    }
}
