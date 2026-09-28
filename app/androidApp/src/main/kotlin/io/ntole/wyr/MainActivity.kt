package io.ntole.wyr

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.ntole.wyr.services.openedFromNotification

/**
 * Binds the shared UI to the Android activity lifecycle, and hands on the intent a notification's tap
 * opened it with, and nothing else — DI is started in [WyrApplication], and every screen lives in
 * `:app:shared`.
 *
 * There is deliberately no `@Preview` of [App] here: it resolves its ViewModel from Koin, which
 * the preview renderer never starts, so the preview would only ever throw.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A tap on a decision's notification opened the app (CLAUDE.md §8a, Push tokens); a rotation's
        // activity, made anew with the same intent, is not a tap.
        if (savedInstanceState == null) openedFromNotification(intent)

        setContent {
            App()
        }
    }

    /** The app was open already when a decision's notification was tapped. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openedFromNotification(intent)
    }
}
