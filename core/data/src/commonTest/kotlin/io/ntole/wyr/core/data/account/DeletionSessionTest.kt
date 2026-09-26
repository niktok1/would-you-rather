package io.ntole.wyr.core.data.account

import io.ntole.wyr.core.data.BASE_URL
import io.ntole.wyr.core.data.FakeServer
import io.ntole.wyr.core.data.cache.InMemoryQuestionCache
import io.ntole.wyr.core.data.player.DefaultPlayerRepository
import io.ntole.wyr.core.data.playgames.DefaultPlayGamesRepository
import io.ntole.wyr.core.data.push.DefaultPushTokenRepository
import io.ntole.wyr.core.data.question.DefaultQuestionRepository
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.session.PlayGamesSettled
import io.ntole.wyr.core.domain.account.DeleteAccount
import io.ntole.wyr.core.domain.analytics.Analytics
import io.ntole.wyr.core.domain.playgames.LinkPlayGames
import io.ntole.wyr.core.domain.playgames.PlayGames
import io.ntole.wyr.core.domain.push.DevicePush
import io.ntole.wyr.core.domain.push.KeepPushTokenRegistered
import io.ntole.wyr.core.domain.push.PushPlatform
import io.ntole.wyr.core.network.InMemoryTokenStorage
import io.ntole.wyr.core.network.SessionStore
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.api.AuthApi
import io.ntole.wyr.core.network.api.PlayerApi
import io.ntole.wyr.core.network.api.PushApi
import io.ntole.wyr.core.network.api.QuestionApi
import io.ntole.wyr.core.network.environment.WyrEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A deletion ends the device's session as a logout does (CLAUDE.md §8a, *Deleting an account*),
 * through the real client against [FakeServer]: the fresh guest after it is a session like any other,
 * which the push token follows, and the deletion is the player's choice, so no launch signs the device
 * back in with Play Games, while the Auth page's button still can.
 */
class DeletionSessionTest {
    private val server = FakeServer()
    private val storage = InMemoryTokenStorage()
    private val store = SessionStore(storage, WyrEnvironment.DEV)
    private val client = WyrHttpClient.create(BASE_URL, store, server.engine)
    private val sessions =
        DefaultSessionRepository(AuthApi(client), store, PlayGamesSettled(storage, WyrEnvironment.DEV))
    private val questions = DefaultQuestionRepository(QuestionApi(client), sessions, InMemoryQuestionCache())
    private val players = DefaultPlayerRepository(PlayerApi(client), sessions)
    private val playGames = DefaultPlayGamesRepository(AuthApi(client), sessions)
    private val linking = LinkPlayGames(SignedInToPlayGames(), playGames, sessions, questions, Analytics.None)
    private val deleteAccount =
        DeleteAccount(DefaultAccountRepository(AuthApi(client), sessions), questions, Analytics.None)

    @Test
    fun `a deletion settles who plays here so no launch signs the device back in with Play Games`() =
        runTest {
            // A fresh install's guest, whose launch has not signed in with Play Games yet.
            sessions.ensure()
            assertFalse(playGames.isSettled())

            deleteAccount()

            assertTrue(playGames.isSettled(), "the player's choice, as a logout is")
            // The next launch, once the fresh guest plays: Play Games is signed in, and signs nobody in.
            sessions.ensure()
            server.playGamesCodes["code-1"] = "gp-1"
            assertFalse(linking.automatically())
            assertEquals(emptyList(), server.playGamesSentAs)
            // The Auth page's button still signs the fresh guest in with Play Games.
            assertTrue(linking.manually())
            assertEquals<List<Pair<String?, String>>>(
                listOf("Bearer access-guest2" to "code-1"),
                server.playGamesSentAs,
            )
        }

    @Test
    fun `a deletion's fresh guest gets the push token and no Play Games link`() =
        runTest {
            server.playGamesCodes["code-1"] = "gp-1"
            sessions.ensure()
            val registering =
                backgroundScope.launch(Dispatchers.Unconfined) {
                    KeepPushTokenRegistered(PushOn, DefaultPushTokenRepository(PushApi(client)), sessions).run()
                }
            // The launch signs the guest in with Play Games: linked, the device settled.
            assertTrue(linking.automatically())
            assertTrue(players.stats().playGamesLinked)

            deleteAccount()
            // The next call mints the fresh guest the device plays on as.
            val fresh = players.stats()

            assertEquals("guest2", store.read()?.playerId)
            assertFalse(fresh.playGamesLinked, "the link went with the account")
            awaitRegistrationAs("Bearer access-guest2")
            registering.cancel()
        }

    /**
     * Waits, in real time, for the push token to be registered as [bearer]: the registration runs on the
     * client's threads as the session is stored, not on the test's dispatcher.
     */
    private suspend fun awaitRegistrationAs(bearer: String) =
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                while (server.pushTokensSentAs.toList().none { it.first == bearer }) delay(10)
            }
        }

    /** Play Games signed in on the device, each code a new one, as the server spends each. */
    private class SignedInToPlayGames : PlayGames {
        private var codes = 0
        override val available: Boolean = true

        override suspend fun isAuthenticated(): Boolean = true

        override suspend fun signIn(): Boolean = true

        override suspend fun serverAuthCode(): String = "code-${++codes}"
    }

    /** Pushes on, with one token, which never changes. */
    private object PushOn : DevicePush {
        override val available: Boolean = true
        override val platform: PushPlatform = PushPlatform.ANDROID

        override suspend fun token(): String = "token-1"

        override val newTokens: Flow<String> = emptyFlow()
        override val received: Flow<Unit> = emptyFlow()

        override fun askPermissionOnce() = Unit
    }
}
