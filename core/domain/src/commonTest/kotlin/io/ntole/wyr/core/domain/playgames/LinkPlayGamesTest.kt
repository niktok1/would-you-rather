package io.ntole.wyr.core.domain.playgames

import io.ntole.wyr.core.domain.analytics.Analytics
import io.ntole.wyr.core.domain.analytics.AnalyticsEvent
import io.ntole.wyr.core.domain.analytics.AnalyticsProperty
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.domain.session.CurrentSession
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Signing a device in with Play Games, at launch and from the Auth page (CLAUDE.md §8a). */
class LinkPlayGamesTest {
    private val calls = mutableListOf<String>()
    private val playGames = FakePlayGames(calls)
    private val link = FakeLink(calls)
    private val session = FakeSession()
    private val analytics = RecordingAnalytics(calls)
    private val linking = LinkPlayGames(playGames, link, session, RecordingQuestions(calls), analytics)

    @Test
    fun `a build without Play Games asks it nothing`() =
        runTest {
            playGames.available = false
            session.player.value = "p1"

            assertFalse(linking.automatically())
            assertFalse(linking.manually())
            assertEquals(emptyList(), calls)
            assertFalse(linking.available)
        }

    @Test
    fun `a launch signs the player Play Games signed in to the server with no tap`() =
        runTest {
            session.player.value = "p1"

            assertTrue(linking.automatically())

            assertEquals(listOf("isAuthenticated", "serverAuthCode", "signIn code-1", "identify p1"), calls)
            val signedIn = analytics.events.single()
            assertEquals(AnalyticsEvent.PLAY_GAMES_SIGNED_IN, signedIn.first)
            assertEquals(
                mapOf(AnalyticsProperty.AUTOMATIC to true, AnalyticsProperty.SWITCHED to false),
                signedIn.second,
            )
        }

    /** The first launch mints its guest only as the player starts to play: the sign-in waits for it. */
    @Test
    fun `a launch waits for a session before it signs in`() =
        runTest {
            val launched = async { linking.automatically() }
            testScheduler.advanceUntilIdle()
            assertEquals(emptyList(), calls, "nothing before a session")

            session.player.value = "guest1"

            assertTrue(launched.await())
            assertTrue("signIn code-1" in calls)
        }

    @Test
    fun `a launch on a settled device signs in no more`() =
        runTest {
            session.player.value = "p1"
            link.settled = true

            assertFalse(linking.automatically())
            assertEquals(emptyList(), calls)
        }

    @Test
    fun `a launch with nobody signed in to Play Games asks for no code`() =
        runTest {
            session.player.value = "p1"
            playGames.authenticated = false

            assertFalse(linking.automatically())
            assertEquals(listOf("isAuthenticated"), calls, "and never asks the player to sign in")
        }

    /** The Play Games player was another player's already: the device is theirs now, its queue dropped. */
    @Test
    fun `a sign-in as another player drops the question queue`() =
        runTest {
            session.player.value = "guest1"
            link.answer = "linked-player"

            assertTrue(linking.automatically())

            assertEquals(
                listOf("isAuthenticated", "serverAuthCode", "signIn code-1", "reset", "identify linked-player"),
                calls,
            )
            assertEquals(true, analytics.events.single().second[AnalyticsProperty.SWITCHED])
        }

    @Test
    fun `a launch whose sign-in is refused leaves the player as they are and shows nothing`() =
        runTest {
            session.player.value = "p1"
            link.refuseWith = DomainError.PLAY_GAMES_CODE_REFUSED

            assertFalse(linking.automatically())

            assertEquals(listOf("isAuthenticated", "serverAuthCode", "signIn code-1"), calls)
            assertEquals(emptyList(), analytics.events)
        }

    /** The exchange with Google takes seconds: a login landing meanwhile is the player's later choice. */
    @Test
    fun `a launch whose answer comes after a login leaves the login standing`() =
        runTest {
            session.player.value = "guest1"
            link.loggedInMeanwhile = "bob-player"

            assertFalse(linking.automatically())

            assertEquals(listOf("isAuthenticated", "serverAuthCode", "signIn code-1"), calls, "nobody reset")
            assertEquals(emptyList(), analytics.events)
            assertEquals("bob-player", session.current())
        }

    @Test
    fun `a launch Play Games gives no code for signs in to nothing`() =
        runTest {
            session.player.value = "p1"
            playGames.code = null

            assertFalse(linking.automatically())
            assertEquals(listOf("isAuthenticated", "serverAuthCode"), calls)
        }

    @Test
    fun `the Auth page's button asks the player to sign in to Play Games first`() =
        runTest {
            session.player.value = "p1"
            playGames.authenticated = false
            playGames.signsIn = true

            assertTrue(linking.manually())

            assertEquals(listOf("isAuthenticated", "signIn", "serverAuthCode", "signIn code-1", "identify p1"), calls)
            assertEquals(false, analytics.events.single().second[AnalyticsProperty.AUTOMATIC])
        }

    @Test
    fun `a player who does not sign in to Play Games sends nothing`() =
        runTest {
            session.player.value = "p1"
            playGames.authenticated = false

            assertFalse(linking.manually())
            assertEquals(listOf("isAuthenticated", "signIn"), calls)
        }

    /** A settled device signs in all the same when the player asks: a tap is the player's own doing. */
    @Test
    fun `the button signs in on a settled device and says why it could not`() =
        runTest {
            session.player.value = "p1"
            link.settled = true
            link.refuseWith = DomainError.PLAY_GAMES_UNAVAILABLE

            val refused = assertFailsWith<WyrException> { linking.manually() }

            assertEquals(DomainError.PLAY_GAMES_UNAVAILABLE, refused.error)
            assertEquals(listOf("isAuthenticated", "serverAuthCode", "signIn code-1"), calls)

            playGames.code = null
            assertEquals(DomainError.PLAY_GAMES_UNAVAILABLE, assertFailsWith<WyrException> { linking.manually() }.error)
        }

    private class FakePlayGames(
        private val calls: MutableList<String>,
    ) : PlayGames {
        override var available = true
        var authenticated = true
        var signsIn = false
        var code: String? = "code-1"

        override suspend fun isAuthenticated(): Boolean {
            calls += "isAuthenticated"
            return authenticated
        }

        override suspend fun signIn(): Boolean {
            calls += "signIn"
            authenticated = signsIn
            return signsIn
        }

        override suspend fun serverAuthCode(): String? {
            calls += "serverAuthCode"
            return code
        }
    }

    private inner class FakeLink(
        private val calls: MutableList<String>,
    ) : PlayGamesRepository {
        var settled = false
        var refuseWith: DomainError? = null

        /** The player the server signs in as: the one playing, unless the Play Games player was another's. */
        var answer: String? = null

        /** When set, a login made the device this player while the sign-in was in flight. */
        var loggedInMeanwhile: String? = null

        override fun isSettled(): Boolean = settled

        override suspend fun signIn(serverAuthCode: String): String? {
            calls += "signIn $serverAuthCode"
            refuseWith?.let { throw WyrException(it) }
            loggedInMeanwhile?.let {
                session.player.value = it
                settled = true
                return null
            }
            val player = answer ?: session.player.value ?: "minted"
            session.player.value = player
            settled = true
            return player
        }
    }

    private class FakeSession : CurrentSession {
        val player = MutableStateFlow<String?>(null)

        override fun current(): String? = player.value

        override val sessions: Flow<String> = player.filterNotNull()
    }

    private class RecordingQuestions(
        private val calls: MutableList<String>,
    ) : QuestionRepository {
        override val categories: StateFlow<Set<String>> = MutableStateFlow(emptySet())

        override suspend fun next(): Question = error("signing in serves no question")

        override suspend fun prefetch() = Unit

        override suspend fun setCategories(categories: Set<String>) = Unit

        override suspend fun skip(questionId: String) = Unit

        override suspend fun reset() {
            calls += "reset"
        }
    }

    private class RecordingAnalytics(
        private val calls: MutableList<String>,
    ) : Analytics by Analytics.None {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()

        override fun identify(playerId: String) {
            calls += "identify $playerId"
        }

        override fun track(
            event: String,
            properties: Map<String, Any?>,
        ) {
            events += event to properties
        }
    }
}
