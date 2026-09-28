package io.ntole.wyr.services

import io.ntole.wyr.core.domain.analytics.Analytics
import io.ntole.wyr.core.domain.analytics.AnalyticsEvent
import io.ntole.wyr.core.domain.notice.DecisionNotices
import io.ntole.wyr.core.domain.playgames.LinkPlayGames
import io.ntole.wyr.core.domain.push.DevicePush
import io.ntole.wyr.core.domain.push.KeepPushTokenRegistered
import io.ntole.wyr.core.domain.session.CurrentSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the app does by itself, with no screen asking, for as long as it runs: one for the app's life,
 * as the analytics are, so a rotation's new activity finds it started. The launch signs the player in
 * with Play Games (CLAUDE.md §8a, *Play Games sign-in*), and from then on the device's push token is
 * registered for every session it stores (§8a, *Push tokens*). Whether a moderator decided one of the
 * player's questions ([notices], §8d *Submitting*) is read at launch, for every session stored, each
 * time the app comes back to the foreground, and when a push arrives while it is open.
 *
 * [foreground] is the platform lifecycle's start, which `App` tells it of; the first is the launch.
 * Everything runs in [scope], off every screen, and nothing it does shows but the notice's dot, and the
 * Account screen a tapped notification asks for ([accountAsked]).
 */
class AppServices(
    private val linkPlayGames: LinkPlayGames,
    private val keepPushTokenRegistered: KeepPushTokenRegistered,
    private val notices: DecisionNotices,
    private val session: CurrentSession,
    private val devicePush: DevicePush,
    private val analytics: Analytics,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private var started = false
    private val _accountAsked = MutableStateFlow(false)

    /**
     * Whether a tapped notification asked for the Account screen, which `App` opens and then says so
     * ([accountShownForNotification]): held until then, since the tap comes before the app is drawn.
     */
    val accountAsked: StateFlow<Boolean> = _accountAsked.asStateFlow()

    /**
     * The app came to the foreground: at launch, the first time, it starts what runs by itself, which
     * reads the notice for the session stored; each time after, it reads the notice again.
     */
    fun foreground() {
        if (started) {
            scope.launch { notices.check() }
            return
        }
        started = true
        scope.launch { linkPlayGames.run() }
        scope.launch { keepPushTokenRegistered.run() }
        // The session stored at launch, and every one after it: a login's player has a list of their own.
        scope.launch { session.sessions.collect { notices.check() } }
        scope.launch { devicePush.received.collect { notices.check() } }
    }

    /**
     * The player tapped a notification of a moderator's decision (CLAUDE.md §8a, *Push tokens*): the
     * Account screen opens, where My questions shows it, and the notice is read again.
     */
    fun notificationOpened() {
        analytics.track(AnalyticsEvent.NOTIFICATION_OPENED)
        _accountAsked.value = true
        scope.launch { notices.check() }
    }

    /** `App` opened the Account screen [accountAsked] for. */
    fun accountShownForNotification() {
        _accountAsked.value = false
    }
}
