package io.ntole.wyr.services

import io.ntole.wyr.core.domain.playgames.LinkPlayGames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * What the app does by itself, with no screen asking, for as long as it runs: one for the app's life,
 * as the analytics are, so a rotation's new activity finds it started. The launch signs the player in
 * with Play Games (CLAUDE.md §8a, *Play Games sign-in*).
 *
 * [foreground] is the platform lifecycle's start, which `App` tells it of; the first is the launch.
 * Everything runs in [scope], off every screen, and nothing it does shows.
 */
class AppServices(
    private val linkPlayGames: LinkPlayGames,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private var started = false

    /** The app came to the foreground: at launch, the first time, it starts what runs by itself. */
    fun foreground() {
        if (started) return
        started = true
        scope.launch { linkPlayGames.automatically() }
    }
}
