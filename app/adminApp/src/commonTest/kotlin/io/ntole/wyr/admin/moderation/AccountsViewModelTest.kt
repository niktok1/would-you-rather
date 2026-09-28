package io.ntole.wyr.admin.moderation

import io.ntole.wyr.admin.moderation.FakeModeration.Companion.TOKEN
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.moderation.AccountRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Deleting a player's account on their request, from the Accounts tab (CLAUDE.md §8a, *By a moderator*). */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val moderation = FakeModeration()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `Delete account asks first and sends nothing until confirmed`() =
        runTest(dispatcher) {
            val viewModel = openWithToken()

            viewModel.setAccountToDelete(" Leaving_1 ")
            viewModel.askToDeleteAccount()
            assertEquals(AccountRef.Username("leaving_1"), viewModel.state.value.deleting)
            viewModel.cancelDeleteAccount()
            testScheduler.advanceUntilIdle()

            assertNull(viewModel.state.value.deleting)
            assertEquals(emptyList(), moderation.calls)
        }

    @Test
    fun `nothing asks to delete without a token or with nothing typed`() =
        runTest(dispatcher) {
            val viewModel = open()
            viewModel.setAccountToDelete("leaving_1")
            viewModel.askToDeleteAccount()
            assertNull(viewModel.state.value.deleting, "no token")

            viewModel.setAdminToken(TOKEN)
            viewModel.setAccountToDelete("   ")
            viewModel.askToDeleteAccount()
            assertNull(viewModel.state.value.deleting, "nothing typed")
        }

    @Test
    fun `a confirmed deletion sends the account as named and says it is done`() =
        runTest(dispatcher) {
            val viewModel = openWithToken()
            val id = "0f8fad5b-d9cb-469f-a165-70867728950e"

            viewModel.setAccountToDelete(id)
            viewModel.askToDeleteAccount()
            viewModel.confirmDeleteAccount()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("deleteAccount Id(accountId=$id)"), moderation.calls)
            assertEquals(listOf(TOKEN), moderation.tokens.map { it.value })
            val state = viewModel.state.value
            assertNull(state.deleting)
            assertNull(state.running)
            assertEquals("", state.accounts.typed, "cleared once deleted")
            assertEquals(
                "Deleted the account of account id $id. It cannot be undone.",
                state.outcomesOf(Screen.ACCOUNTS).notice,
            )
        }

    @Test
    fun `an account no player has says so under the field and keeps what was typed`() =
        runTest(dispatcher) {
            moderation.deleteAccount = { throw WyrException(DomainError.PLAYER_NOT_FOUND, "no such account") }
            val viewModel = openWithToken()

            viewModel.setAccountToDelete("nobody_here")
            viewModel.askToDeleteAccount()
            viewModel.confirmDeleteAccount()
            testScheduler.advanceUntilIdle()

            val accounts = viewModel.state.value.accounts
            assertEquals(Failure.Refused(DomainError.PLAYER_NOT_FOUND, detail = "no such account"), accounts.failure)
            assertEquals("nobody_here", accounts.typed)
            assertNull(accounts.outcomes.notice)
        }

    @Test
    fun `what is typed meanwhile stays once the deletion is done`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<Unit>()
            moderation.deleteAccount = { answer.await() }
            val viewModel = openWithToken()
            viewModel.setAccountToDelete("first_one")
            viewModel.askToDeleteAccount()
            viewModel.confirmDeleteAccount()
            testScheduler.advanceUntilIdle()

            viewModel.setAccountToDelete("second_one")
            // One action at a time: a second deletion asked for meanwhile sends nothing.
            viewModel.askToDeleteAccount()
            viewModel.confirmDeleteAccount()
            answer.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("deleteAccount Username(username=first_one)"), moderation.calls)
            assertEquals("second_one", viewModel.state.value.accounts.typed)
        }

    @Test
    fun `Lock forgets the account typed the deletion asked for and what became of the last`() =
        runTest(dispatcher) {
            val viewModel = openWithToken()
            viewModel.setAccountToDelete("gone_now")
            viewModel.askToDeleteAccount()
            viewModel.confirmDeleteAccount()
            testScheduler.advanceUntilIdle()
            viewModel.setAccountToDelete("next_one")
            viewModel.askToDeleteAccount()

            viewModel.lock()

            val state = viewModel.state.value
            assertEquals(AccountDeletions(), state.accounts)
            assertNull(state.deleting)
        }

    private fun TestScope.open(): ModerationViewModel =
        moderationViewModelOver(moderation, FakeCategories()).also { testScheduler.advanceUntilIdle() }

    private fun TestScope.openWithToken(): ModerationViewModel = open().also { it.setAdminToken(TOKEN) }
}
