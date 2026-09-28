package io.ntole.wyr.core.domain.account

import io.ntole.wyr.core.domain.analytics.Analytics
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.domain.session.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AccountUseCasesTest {
    private val calls = mutableListOf<String>()
    private val accounts = RecordingAccounts(calls)
    private val sessions = RecordingSessions(calls)
    private val questions = RecordingQuestions(calls)
    private val analytics = RecordingAnalytics(calls)

    @Test
    fun `a registration ensures the session then registers the name as typed`() =
        runTest {
            val username = RegisterAccount(accounts, sessions, analytics)("Bob_1", "correct horse")

            assertEquals("bob_1", username)
            // The name untouched: lower-casing it is the server's, which answers with it as kept.
            assertEquals(listOf("ensure", "register Bob_1"), calls.take(2))
        }

    /** The player registered is who the install's analytics are from now on (CLAUDE.md §8g), by id. */
    @Test
    fun `a registration that worked identifies the player by id`() =
        runTest {
            RegisterAccount(accounts, sessions, analytics)("Bob_1", "correct horse")

            assertEquals(listOf("ensure", "register Bob_1", "ensure", "identify p1"), calls)
        }

    @Test
    fun `a refused registration identifies nobody`() =
        runTest {
            accounts.refuseRegistrations = true

            assertFailsWith<WyrException> { RegisterAccount(accounts, sessions, analytics)("Bob_1", "correct horse") }

            assertFalse(calls.any { it.startsWith("identify") }, "$calls")
        }

    @Test
    fun `a registration the rules refuse sends nothing and names nothing typed`() =
        runTest {
            val registerAccount = RegisterAccount(accounts, sessions, analytics)

            listOf("a b" to "correct horse", "bob_1" to "short").forEach { (username, password) ->
                val refused = assertFailsWith<IllegalArgumentException> { registerAccount(username, password) }
                assertFalse(password in refused.message.orEmpty(), "the password in \"${refused.message}\"")
            }

            // Not even the session: on a cold start ensuring it would have minted a guest.
            assertEquals(emptyList(), calls)
        }

    @Test
    fun `a login drops the queue the player before filled`() =
        runTest {
            LogIn(accounts, questions, sessions, analytics)("bob_1", "correct horse")

            assertEquals(listOf("logIn bob_1", "reset"), calls.take(2))
        }

    /** The player of the session the login stored, which ensuring finds without minting. */
    @Test
    fun `a login identifies the player it logged in to`() =
        runTest {
            LogIn(accounts, questions, sessions, analytics)("bob_1", "correct horse")

            assertEquals(listOf("logIn bob_1", "reset", "ensure", "identify p1"), calls)
        }

    @Test
    fun `a refused login leaves the queue as it was`() =
        runTest {
            accounts.refuseLogins = true

            val refused =
                assertFailsWith<WyrException> {
                    LogIn(
                        accounts,
                        questions,
                        sessions,
                        analytics,
                    )("bob_1", "wrong horse")
                }

            assertEquals(DomainError.INVALID_LOGIN, refused.error)
            // Nobody identified either.
            assertEquals(listOf("logIn bob_1"), calls)
        }

    @Test
    fun `a logout drops the queue the account filled`() =
        runTest {
            LogOut(accounts, questions, analytics)()

            assertEquals(listOf("logOut", "reset"), calls.take(2))
        }

    /** Every event after a logout is a fresh anonymous player's, joined to nobody before (§8g). */
    @Test
    fun `a logout has analytics forget the player`() =
        runTest {
            LogOut(accounts, questions, analytics)()

            assertEquals(listOf("logOut", "reset", "analytics reset"), calls)
        }

    /** Gone, then the queue it filled and who the analytics thought played (CLAUDE.md §8g). */
    @Test
    fun `a deletion drops the queue and has analytics forget the player once it worked`() =
        runTest {
            DeleteAccount(accounts, questions, analytics)()

            assertEquals(listOf("deleteAccount", "reset", "track account_deleted", "analytics reset"), calls)
        }

    @Test
    fun `a deletion that failed forgets nothing`() =
        runTest {
            accounts.refuseDeletions = true

            assertFailsWith<WyrException> { DeleteAccount(accounts, questions, analytics)() }

            assertEquals(listOf("deleteAccount"), calls)
        }

    private class RecordingAccounts(
        private val calls: MutableList<String>,
    ) : AccountRepository {
        var refuseLogins = false
        var refuseRegistrations = false
        var refuseDeletions = false

        override suspend fun register(
            username: String,
            password: String,
        ): String {
            calls += "register $username"
            if (refuseRegistrations) throw WyrException(DomainError.USERNAME_TAKEN)
            return username.lowercase()
        }

        override suspend fun logIn(
            username: String,
            password: String,
        ) {
            calls += "logIn $username"
            if (refuseLogins) throw WyrException(DomainError.INVALID_LOGIN)
        }

        override suspend fun logOut() {
            calls += "logOut"
        }

        override suspend fun deleteAccount() {
            calls += "deleteAccount"
            if (refuseDeletions) throw WyrException(DomainError.NETWORK)
        }
    }

    private class RecordingSessions(
        private val calls: MutableList<String>,
    ) : SessionRepository {
        override suspend fun ensure(): String {
            calls += "ensure"
            return "p1"
        }
    }

    private class RecordingQuestions(
        private val calls: MutableList<String>,
    ) : QuestionRepository {
        override val categories: StateFlow<Set<String>> = MutableStateFlow(emptySet())

        override suspend fun next(): Question = error("an account serves no question")

        override suspend fun prefetch() = Unit

        override suspend fun setCategories(categories: Set<String>) = Unit

        override suspend fun skip(questionId: String) = Unit

        override suspend fun reset() {
            calls += "reset"
        }
    }

    /** Only who the analytics are told the player is, and when they are told to forget. */
    private class RecordingAnalytics(
        private val calls: MutableList<String>,
    ) : Analytics by Analytics.None {
        override fun identify(playerId: String) {
            calls += "identify $playerId"
        }

        override fun reset() {
            calls += "analytics reset"
        }

        override fun track(
            event: String,
            properties: Map<String, Any?>,
        ) {
            calls += "track $event"
        }
    }
}
