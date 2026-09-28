package io.ntole.wyr.services

import io.ntole.wyr.core.domain.analytics.Analytics
import io.ntole.wyr.core.domain.analytics.AnalyticsEvent
import io.ntole.wyr.core.domain.notice.DecisionNotices
import io.ntole.wyr.core.domain.notice.SeenDecisions
import io.ntole.wyr.core.domain.notice.SeenDecisionsStore
import io.ntole.wyr.core.domain.playgames.LinkPlayGames
import io.ntole.wyr.core.domain.playgames.PlayGames
import io.ntole.wyr.core.domain.playgames.PlayGamesRepository
import io.ntole.wyr.core.domain.push.DevicePush
import io.ntole.wyr.core.domain.push.KeepPushTokenRegistered
import io.ntole.wyr.core.domain.push.PushPlatform
import io.ntole.wyr.core.domain.push.PushTokenRepository
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.domain.session.CurrentSession
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the app does by itself as it comes to the foreground (CLAUDE.md §8a, *Play Games sign-in*). */
class AppServicesTest {
    private val signIns = mutableListOf<String>()
    private val tokensRegistered = mutableListOf<String>()
    private var listReads = 0
    private val analytics = TrackedEvents()
    private val session = FakeSession()

    @Test
    fun `the launch signs in with Play Games once and coming back does not again`() =
        runTest {
            session.player.value = "p1"
            val services = services()

            services.foreground()
            testScheduler.runCurrent()
            services.foreground()
            services.foreground()
            testScheduler.runCurrent()

            assertEquals(listOf("code"), signIns)
        }

    /** A first launch has no session until the player plays, and the sign-in waits for it. */
    @Test
    fun `the launch's sign-in waits for the session the player starts playing with`() =
        runTest {
            val services = services()

            services.foreground()
            testScheduler.runCurrent()
            assertEquals(emptyList(), signIns)

            session.player.value = "guest1"
            testScheduler.runCurrent()
            assertEquals(listOf("code"), signIns)
        }

    /** The push token is registered from the launch on, for the session stored then and every one after. */
    @Test
    fun `the launch keeps the push token registered for every session`() =
        runTest {
            session.player.value = "p1"
            val services = services()

            services.foreground()
            testScheduler.runCurrent()
            session.player.value = "p2"
            testScheduler.runCurrent()
            services.foreground()
            testScheduler.runCurrent()

            assertEquals(listOf("p1 token", "p2 token"), tokensRegistered)
        }

    /**
     * Whether a moderator decided a question of the player's is read at launch for the session stored,
     * for every session after, each time the app comes back, and when a push arrives while it is open
     * (CLAUDE.md §8d, *Submitting*); with no session, never.
     */
    @Test
    fun `the notice is read at launch for every session on coming back and when a push arrives`() =
        runTest {
            val services = services()

            services.foreground()
            testScheduler.runCurrent()
            assertEquals(0, listReads, "no session, nothing to read")

            session.player.value = "p1"
            testScheduler.runCurrent()
            assertEquals(1, listReads, "the session stored")

            services.foreground()
            testScheduler.runCurrent()
            assertEquals(2, listReads, "back in the foreground")

            TokenPush.received.emit(Unit)
            testScheduler.runCurrent()
            assertEquals(3, listReads, "a push while open")
        }

    /** A tapped notification asks for the Account screen until the app has opened it, and is reported. */
    @Test
    fun `a tapped notification asks for the Account screen and reads the notice`() =
        runTest {
            session.player.value = "p1"
            val services = services()
            assertEquals(false, services.accountAsked.value)

            services.notificationOpened()
            testScheduler.runCurrent()

            assertEquals(true, services.accountAsked.value)
            assertEquals(1, listReads)
            assertEquals(listOf(AnalyticsEvent.NOTIFICATION_OPENED), analytics.tracked)
            services.accountShownForNotification()
            assertEquals(false, services.accountAsked.value)
        }

    private fun TestScope.services(): AppServices =
        AppServices(
            linkPlayGames = LinkPlayGames(SignedInPlayGames, Link(), session, NoQuestions, Analytics.None),
            keepPushTokenRegistered = KeepPushTokenRegistered(TokenPush, Tokens(), session),
            notices = DecisionNotices(Listed(), session, NoSeen),
            session = session,
            devicePush = TokenPush,
            analytics = analytics,
            scope = backgroundScope,
        )

    private inner class Listed : SubmissionRepository {
        override suspend fun submit(
            optionA: String,
            optionB: String,
            categories: Set<String>,
        ): Submission = error("nothing is submitted here")

        override suspend fun mine(): List<Submission> {
            listReads++
            return emptyList()
        }
    }

    private class TrackedEvents : Analytics by Analytics.None {
        val tracked = mutableListOf<String>()

        override fun track(
            event: String,
            properties: Map<String, Any?>,
        ) {
            tracked += event
        }
    }

    private object NoSeen : SeenDecisionsStore {
        override fun read(): SeenDecisions? = null

        override suspend fun write(seen: SeenDecisions) = Unit
    }

    /** Pushes with a token, as Firebase gives one. */
    private object TokenPush : DevicePush by DevicePush.None {
        override val available = true

        override suspend fun token() = "token"

        override val received = MutableSharedFlow<Unit>()
    }

    private inner class Tokens : PushTokenRepository {
        override suspend fun register(
            token: String,
            platform: PushPlatform,
        ) {
            tokensRegistered += "${session.current()} $token"
        }
    }

    /** Play Games that signed the player in by itself, as it does at launch. */
    private object SignedInPlayGames : PlayGames {
        override val available = true

        override suspend fun isAuthenticated() = true

        override suspend fun signIn() = true

        override suspend fun serverAuthCode() = "code"

        override suspend fun playerName(): String? = null
    }

    private inner class Link : PlayGamesRepository {
        private var settled = false

        override fun isSettled() = settled

        override suspend fun signIn(serverAuthCode: String): String? {
            signIns += serverAuthCode
            settled = true
            return session.player.value ?: "minted"
        }
    }

    private class FakeSession : CurrentSession {
        val player = MutableStateFlow<String?>(null)

        override fun current(): String? = player.value

        override val sessions: Flow<String> = player.filterNotNull()
    }

    private object NoQuestions : QuestionRepository {
        override val categories: StateFlow<Set<String>> = MutableStateFlow(emptySet())

        override suspend fun next(): Question = error("no question here")

        override suspend fun prefetch() = Unit

        override suspend fun setCategories(categories: Set<String>) = Unit

        override suspend fun skip(questionId: String) = Unit

        override suspend fun reset() = Unit
    }
}
