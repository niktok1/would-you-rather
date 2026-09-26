package io.ntole.wyr.loading

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.ntole.wyr.account.AccountAction
import io.ntole.wyr.account.AccountActions
import io.ntole.wyr.account.AccountScreen
import io.ntole.wyr.account.AccountState
import io.ntole.wyr.account.AuthMode
import io.ntole.wyr.categories.CategoriesActions
import io.ntole.wyr.categories.CategoriesScreen
import io.ntole.wyr.categories.CategoriesState
import io.ntole.wyr.core.network.environment.WyrEnvironment
import io.ntole.wyr.everyText
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.WyrStrings
import io.ntole.wyr.language.stringsOf
import io.ntole.wyr.play.PlayScreen
import io.ntole.wyr.play.PlayUiState
import io.ntole.wyr.renderAt
import io.ntole.wyr.theme.WyrTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A slow first load (CLAUDE.md §8d, *A slow first load*): a free Render service sleeps and its first
 * request takes up to a minute, so a spinner that has turned for 5 seconds says one short line under
 * it, on the Play screen, the Categories screen and the Account screen, and nothing else. The scene's
 * frame clock is stepped a frame at a time, as a phone draws them, never waited for.
 */
class LoadingSpinnerDrawTest {
    @Test
    fun `a spinner says a moment more once it has turned for 5 seconds and not before`() {
        Language.entries.forEach { language ->
            val still = stringsOf(language).stillLoading
            SCREENS.forEach { (name, screen) ->
                val scene =
                    ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
                        WyrTheme { WyrStrings(language) { screen() } }
                    }
                try {
                    stepTo(scene, SLOW_AFTER - 100.milliseconds)
                    assertFalse(still in scene.everyText(), "$language, $name: said too soon")

                    stepTo(scene, SLOW_AFTER + 200.milliseconds, from = SLOW_AFTER - 100.milliseconds)
                    assertEquals(1, scene.everyText().count { it == still }, "$language, $name: ${scene.everyText()}")
                } finally {
                    scene.close()
                }
            }
        }
    }

    @Test
    fun `the line waits 5 seconds`() {
        assertEquals(5.seconds, SLOW_AFTER)
        assertEquals("Још мало…", stringsOf(Language.SERBIAN_CYRILLIC).stillLoading)
    }

    /** Draws a frame every 16 ms from [from] to [to], as a phone does. */
    private fun stepTo(
        scene: ImageComposeScene,
        to: Duration,
        from: Duration = Duration.ZERO,
    ) {
        var at = from
        while (at <= to) {
            scene.renderAt(at.inWholeNanoseconds)
            at += FRAME
        }
    }

    private object NoAccountActions : AccountActions {
        override fun refresh() = Unit

        override fun authShown() = Unit

        override fun setAuthMode(mode: AuthMode) = Unit

        override fun leftAuth() = Unit

        override fun setRegisterUsername(text: String) = Unit

        override fun setRegisterPassword(text: String) = Unit

        override fun toggleShowRegisterPassword() = Unit

        override fun register() = Unit

        override fun setLoginUsername(text: String) = Unit

        override fun setLoginPassword(text: String) = Unit

        override fun logIn() = Unit

        override fun cancelLogIn() = Unit

        override fun logOut() = Unit

        override fun deleteAccount() = Unit
    }

    private object NoCategoriesActions : CategoriesActions {
        override fun search(query: String) = Unit

        override fun toggle(id: String) = Unit

        override fun selectAll() = Unit

        override fun refresh() = Unit

        override fun play() = Unit
    }

    private companion object {
        const val WIDTH = 375
        const val HEIGHT = 599
        val FRAME = 16.milliseconds

        /** Each screen as it shows while it loads, the first read of all under way. */
        val SCREENS: List<Pair<String, @Composable () -> Unit>> =
            listOf(
                "Play" to {
                    PlayScreen(
                        state = PlayUiState.Loading,
                        points = null,
                        onChoose = {},
                        onSkip = {},
                        onNext = {},
                        onReact = {},
                        onRetry = {},
                    )
                },
                "Categories" to {
                    CategoriesScreen(state = CategoriesState(isLoading = true), actions = NoCategoriesActions)
                },
                "Account" to {
                    AccountScreen(
                        state = AccountState(running = AccountAction.LOAD),
                        actions = NoAccountActions,
                        environment = WyrEnvironment.PROD,
                        language = Language.DEFAULT,
                        onSelectLanguage = {},
                        statisticsOn = true,
                        onStatisticsChange = {},
                        onOpenAuth = {},
                        onNewQuestion = {},
                    )
                },
            )
    }
}
