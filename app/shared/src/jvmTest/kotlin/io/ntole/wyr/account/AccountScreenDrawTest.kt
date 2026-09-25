package io.ntole.wyr.account

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.theme.WyrTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * The Account screen drawn off screen at a phone's size, in each theme, from every state it can be
 * in. Compose measures and draws it all, so a layout that cannot be measured fails here rather than
 * when the tab opens.
 */
class AccountScreenDrawTest {
    @Test
    fun `the screen draws in every state it can be in`() {
        val guest = PlayerStats("guest1", 12, 12, 10, 1, 4, 0)
        val states =
            listOf(
                AccountState(),
                AccountState(running = AccountAction.LOAD),
                AccountState(failure = AccountFailure(AccountAction.LOAD, DomainError.NETWORK)),
                AccountState(stats = guest),
                AccountState(
                    stats = guest,
                    registerUsername = "a b",
                    registerPassword = "short",
                    showRegisterPassword = true,
                    loginUsername = "bob_1",
                    loginPassword = "correct horse",
                    guestPointsWarning = 12,
                    failure = AccountFailure(AccountAction.LOG_IN, DomainError.RATE_LIMITED, 42.seconds),
                    running = AccountAction.LOG_IN,
                ),
                AccountState(stats = guest.copy(username = "bob_1")),
                AccountState(
                    stats = guest.copy(username = "bob_1"),
                    failure = AccountFailure(AccountAction.LOG_OUT, DomainError.NETWORK),
                ),
            )

        states.forEach { state -> listOf(false, true).forEach { dark -> draw(state, dark) } }
    }

    private fun draw(
        state: AccountState,
        dark: Boolean,
    ) {
        val scene =
            ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
                WyrTheme(darkTheme = dark) { AccountScreen(state = state, actions = NoActions) }
            }
        try {
            assertEquals(WIDTH, scene.render().width)
        } finally {
            scene.close()
        }
    }

    private object NoActions : AccountActions {
        override fun refresh() = Unit

        override fun setRegisterUsername(text: String) = Unit

        override fun setRegisterPassword(text: String) = Unit

        override fun toggleShowRegisterPassword() = Unit

        override fun register() = Unit

        override fun setLoginUsername(text: String) = Unit

        override fun setLoginPassword(text: String) = Unit

        override fun logIn() = Unit

        override fun cancelLogIn() = Unit

        override fun logOut() = Unit
    }

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 900
    }
}
