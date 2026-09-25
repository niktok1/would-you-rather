package io.ntole.wyr.core.domain.account

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

    @Test
    fun `a registration ensures the session then registers the name as typed`() =
        runTest {
            val username = RegisterAccount(accounts, sessions)("Bob_1", "correct horse")

            assertEquals("bob_1", username)
            // The name untouched: lower-casing it is the server's, which answers with it as kept.
            assertEquals(listOf("ensure", "register Bob_1"), calls)
        }

    @Test
    fun `a registration the rules refuse sends nothing and names nothing typed`() =
        runTest {
            val registerAccount = RegisterAccount(accounts, sessions)

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
            LogIn(accounts, questions)("bob_1", "correct horse")

            assertEquals(listOf("logIn bob_1", "reset"), calls)
        }

    @Test
    fun `a refused login leaves the queue as it was`() =
        runTest {
            accounts.refuseLogins = true

            val refused = assertFailsWith<WyrException> { LogIn(accounts, questions)("bob_1", "wrong horse") }

            assertEquals(DomainError.INVALID_LOGIN, refused.error)
            assertEquals(listOf("logIn bob_1"), calls)
        }

    @Test
    fun `a logout drops the queue the account filled`() =
        runTest {
            LogOut(accounts, questions)()

            assertEquals(listOf("logOut", "reset"), calls)
        }

    private class RecordingAccounts(
        private val calls: MutableList<String>,
    ) : AccountRepository {
        var refuseLogins = false

        override suspend fun register(
            username: String,
            password: String,
        ): String {
            calls += "register $username"
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
}
