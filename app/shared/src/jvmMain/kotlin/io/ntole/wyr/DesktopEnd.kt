package io.ntole.wyr

import io.ntole.wyr.analytics.UsageTracker
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.mp.KoinPlatform
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long the desktop app waits, once its window is closed, for its last analytics to be sent: long
 * enough for a request that is answered, short enough that an app offline still ends at once.
 */
internal val LAST_SEND_WAIT: Duration = 2.seconds

/**
 * The desktop app ends, its window closed (CLAUDE.md §8g): the app goes to the background, and the
 * analytics waiting are sent before the JVM ends, which waits for no send under way, [LAST_SEND_WAIT]
 * at most. Called by `main` once the application has ended, the window gone, and before the process
 * exits.
 */
fun endDesktopApp() = endApp(KoinPlatform.getKoin().get(), LAST_SEND_WAIT)

/** [usage] ended, waiting [wait] at most for what it sends. */
internal fun endApp(
    usage: UsageTracker,
    wait: Duration,
) {
    runBlocking { withTimeoutOrNull(wait) { usage.end() } }
}
