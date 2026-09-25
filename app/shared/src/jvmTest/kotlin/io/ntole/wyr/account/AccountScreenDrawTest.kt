package io.ntole.wyr.account

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.theme.WyrTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The Account screen drawn off screen at two phones' sizes, in each theme, from every state it can be
 * in. Compose measures and draws it all, so a layout that cannot be measured fails here rather than
 * when the tab opens. Whether what it draws fits is asked separately: the screen scrolls, so it draws
 * whatever its height.
 */
class AccountScreenDrawTest {
    @Test
    fun `the screen draws in every state it can be in`() {
        (GUEST_STATES + WITHOUT_FORMS).forEach { state ->
            listOf(false, true).forEach { dark ->
                draw(state, dark, WIDTH, HEIGHT)
                draw(state, dark, SHORT_PHONE_WIDTH, SHORT_PHONE_HEIGHT)
            }
        }
    }

    /** Each of the player's stats shows as a line of its own, a guest's and a registered player's. */
    @Test
    fun `the screen shows every stat`() {
        listOf(GUEST, REGISTERED).forEach { stats ->
            val shown = textsShown(AccountState(stats = stats))
            statLines(stats).forEach { line -> assertTrue(line in shown, "\"$line\" is not in $shown") }
        }
    }

    /**
     * A registered player's screen, and one with no player read yet, has no form, so it must not need
     * scrolling: the stats and Log out all show at an iPhone SE's height. A guest's scrolls to its
     * forms, which come under the same heading and the same lines as a registered player's, so its
     * stats show before any scrolling too.
     *
     * Measured at the width drawn above, not 375, since CI's Linux fonts wrap wider than a phone's
     * (as `PlayScreenDrawTest` explains). On this Mac the tallest, a registered player whose read
     * again failed, needs 509 of the 599.
     */
    @Test
    fun `every state without a form fits a short phone whole`() {
        WITHOUT_FORMS.forEach { state ->
            val needed = heightNeeded(state, WIDTH)
            assertTrue(needed <= SHORT_PHONE_HEIGHT, "$state needs $needed of $SHORT_PHONE_HEIGHT")
        }
    }

    private fun draw(
        state: AccountState,
        dark: Boolean,
        width: Int,
        height: Int,
    ) {
        val scene =
            ImageComposeScene(width = width, height = height, density = Density(1f)) {
                WyrTheme(darkTheme = dark) { Screen(state) }
            }
        try {
            assertEquals(width, scene.render().width)
        } finally {
            scene.close()
        }
    }

    /** The least height [state]'s screen needs at [width] to show all of it without scrolling. */
    private fun heightNeeded(
        state: AccountState,
        width: Int,
    ): Int {
        var needed = -1
        val scene =
            ImageComposeScene(width = width, height = SHORT_PHONE_HEIGHT, density = Density(1f)) {
                WyrTheme {
                    Layout(content = { Screen(state) }) { measurables, constraints ->
                        val screen = measurables.single()
                        needed = screen.minIntrinsicHeight(constraints.maxWidth)
                        val placeable = screen.measure(constraints)
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                }
            }
        try {
            scene.render()
        } finally {
            scene.close()
        }
        assertTrue(needed > 0, "$state was never measured")
        return needed
    }

    /** Every text [state]'s screen draws, as its semantics hold it. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun textsShown(state: AccountState): List<String> {
        val scene =
            ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
                WyrTheme { Screen(state) }
            }
        try {
            scene.render()
            return scene.semanticsOwners
                .flatMap { owner -> owner.getAllSemanticsNodes(mergingEnabled = false) }
                .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                .map { it.text }
        } finally {
            scene.close()
        }
    }

    @Composable
    private fun Screen(state: AccountState) {
        AccountScreen(state = state, actions = NoActions)
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

        /** An iPhone SE (667 high) less its status bar (20) and the tab row above the tab (48). */
        const val SHORT_PHONE_WIDTH = 375
        const val SHORT_PHONE_HEIGHT = 599

        val GUEST = PlayerStats("guest1", 12, 12, 10, 1, 4, 0)

        /** The longest name there can be, and numbers long enough to widen every line. */
        val REGISTERED =
            PlayerStats("p1", 123_456, 123_456, 12_345, 1_234, 12_345, 123_456, username = "abcdefghijklmnopqrst")

        /** A guest's screen, with the forms. */
        val GUEST_STATES =
            listOf(
                AccountState(stats = GUEST),
                AccountState(stats = GUEST.copy(totalPoints = 1, answersGiven = 1, questionsAnswered = 1)),
                AccountState(
                    stats = GUEST,
                    registerUsername = "a b",
                    registerPassword = "short",
                    showRegisterPassword = true,
                    loginUsername = "bob_1",
                    loginPassword = "correct horse",
                    guestPointsWarning = 12,
                    failure = AccountFailure(AccountAction.LOG_IN, DomainError.RATE_LIMITED, 42.seconds),
                    running = AccountAction.LOG_IN,
                ),
            )

        /** Every state with no form: no player read yet, and a registered player. */
        val WITHOUT_FORMS =
            listOf(
                AccountState(),
                AccountState(running = AccountAction.LOAD),
                AccountState(failure = AccountFailure(AccountAction.LOAD, DomainError.NETWORK)),
                AccountState(stats = REGISTERED),
                AccountState(stats = REGISTERED, running = AccountAction.LOAD),
                AccountState(stats = REGISTERED, running = AccountAction.LOG_OUT),
                AccountState(stats = REGISTERED, failure = AccountFailure(AccountAction.LOG_OUT, DomainError.NETWORK)),
                AccountState(stats = REGISTERED, failure = AccountFailure(AccountAction.LOAD, DomainError.NETWORK)),
            )
    }
}
