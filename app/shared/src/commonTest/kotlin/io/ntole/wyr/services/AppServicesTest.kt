package io.ntole.wyr.services

import io.ntole.wyr.core.domain.analytics.Analytics
import io.ntole.wyr.core.domain.playgames.LinkPlayGames
import io.ntole.wyr.core.domain.playgames.PlayGames
import io.ntole.wyr.core.domain.playgames.PlayGamesRepository
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.domain.session.CurrentSession
import kotlinx.coroutines.flow.Flow
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

    private fun TestScope.services(): AppServices =
        AppServices(
            linkPlayGames = LinkPlayGames(SignedInPlayGames, Link(), session, NoQuestions, Analytics.None),
            scope = backgroundScope,
        )

    /** Play Games that signed the player in by itself, as it does at launch. */
    private object SignedInPlayGames : PlayGames {
        override val available = true

        override suspend fun isAuthenticated() = true

        override suspend fun signIn() = true

        override suspend fun serverAuthCode() = "code"
    }

    private inner class Link : PlayGamesRepository {
        private var settled = false

        override fun isSettled() = settled

        override suspend fun signIn(serverAuthCode: String): String {
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
