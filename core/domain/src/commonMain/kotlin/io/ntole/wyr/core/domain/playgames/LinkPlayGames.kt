package io.ntole.wyr.core.domain.playgames

import io.ntole.wyr.core.domain.analytics.Analytics
import io.ntole.wyr.core.domain.analytics.AnalyticsEvent
import io.ntole.wyr.core.domain.analytics.AnalyticsProperty
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.domain.session.CurrentSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Signs this device in with Google Play Games Services (CLAUDE.md §8a, *Play Games sign-in*): the
 * user's no-click register, the Auth page's forms staying as the fallback.
 *
 * [automatically] is the launch's: once there is a session, and while who plays here is unsettled
 * ([PlayGamesRepository.isSettled]), a player Play Games signed in by itself is signed in to the
 * server, with no tap. [manually] is the Auth page's button. Either way the server's answer is stored
 * in place of the device's session, as a login's is; when that is another player, the question queue,
 * filled from the one before's feed, is dropped, and [analytics] hear who plays now (§8g).
 *
 * One at a time: bound once for the app, so the launch's and a tap cannot both sign in.
 */
public class LinkPlayGames(
    private val playGames: PlayGames,
    private val link: PlayGamesRepository,
    private val session: CurrentSession,
    private val questions: QuestionRepository,
    private val analytics: Analytics,
) {
    private val mutex = Mutex()

    /** Whether this build has Play Games, for the Auth page to offer it. */
    public val available: Boolean get() = playGames.available

    /**
     * At launch: waits for a session, then, while who plays here is unsettled and Play Games says the
     * player is signed in to it, signs in to the server with it. Returns whether it did. Never throws but
     * the caller's cancellation: a refusal, Google not answering or being offline leaves the player as
     * they are and nothing is shown, and the next launch tries again; a login or a logout landing while
     * it is in flight stands, and settles the device.
     */
    public suspend fun automatically(): Boolean {
        if (!playGames.available) return false
        // Once a session exists: the first launch mints one only as the player starts to play.
        session.sessions.first()
        return mutex.withLock {
            if (link.isSettled() || !playGames.isAuthenticated()) return@withLock false
            try {
                signIn(automatic = true)
            } catch (failed: WyrException) {
                false
            }
        }
    }

    /**
     * The Auth page's button: asks the player to sign in to Play Games unless they are, then signs in to
     * the server with it. Returns false, with nothing sent, when they did not sign in to Play Games, and
     * with nothing stored, when the device became another player while it was in flight.
     *
     * @throws WyrException when the server could not sign them in, the stored session left as it was.
     */
    public suspend fun manually(): Boolean {
        if (!playGames.available) return false
        return mutex.withLock {
            if (!playGames.isAuthenticated() && !playGames.signIn()) return@withLock false
            signIn(automatic = false)
        }
    }

    /** Whether the device plays as the Play Games player now: false when it became another meanwhile. */
    private suspend fun signIn(automatic: Boolean): Boolean {
        val before = session.current()
        val code =
            playGames.serverAuthCode()
                ?: throw WyrException(DomainError.PLAY_GAMES_UNAVAILABLE, "Play Games gave no server auth code")
        val player = link.signIn(code) ?: return false
        val switched = player != before
        // The queue was filled from the feed of the player before.
        if (switched) questions.reset()
        analytics.identify(player)
        analytics.track(
            AnalyticsEvent.PLAY_GAMES_SIGNED_IN,
            mapOf(AnalyticsProperty.AUTOMATIC to automatic, AnalyticsProperty.SWITCHED to switched),
        )
        return true
    }
}
