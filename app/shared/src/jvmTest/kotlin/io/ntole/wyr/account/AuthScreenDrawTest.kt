package io.ntole.wyr.account

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import io.ntole.wyr.RecordingUris
import io.ntole.wyr.about.Site
import io.ntole.wyr.about.SitePage
import io.ntole.wyr.assertInCentredColumn
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.descriptions
import io.ntole.wyr.everyNode
import io.ntole.wyr.everyText
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.WyrStrings
import io.ntole.wyr.language.fill
import io.ntole.wyr.language.stringsOf
import io.ntole.wyr.sizeNeeded
import io.ntole.wyr.tap
import io.ntole.wyr.theme.WyrTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The Auth page (CLAUDE.md §8d, *The Account screen*) drawn off screen at two phones' sizes, in each
 * theme and each language, from every state it can be in, and read and tapped through its semantics.
 */
class AuthScreenDrawTest {
    @Test
    fun `the page draws in every state it can be in`() {
        STATES.forEach { state ->
            listOf(false, true).forEach { dark ->
                Language.entries.forEach { language ->
                    listOf(WIDTH to HEIGHT, SHORT_PHONE_WIDTH to SHORT_PHONE_HEIGHT).forEach { (width, height) ->
                        val scene = scene(state, language, dark = dark, width = width, height = height)
                        try {
                            assertEquals(width, scene.render().width)
                        } finally {
                            scene.close()
                        }
                    }
                }
            }
        }
    }

    /**
     * Two fields, a button and a link, or the warning's two buttons in the button's place, and a failed
     * read with Try again over them before any player is read: the page needs no scrolling on an
     * iPhone SE in any state and any language. Measured 400 wide, as the Account screen is, since CI's
     * Linux fonts wrap wider than a phone's.
     */
    @Test
    fun `every state fits a short phone whole in every language`() {
        STATES.forEach { state ->
            Language.entries.forEach { language ->
                val (_, height) =
                    sizeNeeded(WIDTH, SHORT_PHONE_HEIGHT) {
                        WyrTheme { WyrStrings(language) { AuthScreen(state = state, actions = Recorder()) } }
                    }
                assertTrue(height <= SHORT_PHONE_HEIGHT, "$state in $language needs $height of $SHORT_PHONE_HEIGHT")
            }
        }
    }

    /** Register only: the two fields with their rules, the show toggle, the button and the link to Log in. */
    @Test
    fun `the page opens on the register form`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).accountScreens
            val shown = textsOf(AccountState(stats = GUEST), language)

            listOf(
                strings.username,
                strings.password,
                usernameRule(strings),
                passwordRule(strings),
                strings.show,
                strings.register,
                strings.toLogIn,
            ).forEach { text -> assertTrue(text in shown, "$language: \"$text\" is not in $shown") }
            assertFalse(strings.logIn in shown, "$language: $shown")
            assertFalse(strings.toRegister in shown, "$language: $shown")
        }
    }

    /**
     * Under Register, one short line: registering accepts the terms and the privacy policy, each noun a
     * link to its page on the site in the language shown (CLAUDE.md §8d, *The Account screen*).
     */
    @Test
    fun `the register form's line under its button links the terms and the privacy policy`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).accountScreens
            val line = strings.termsLine.line.fill(strings.termsLine.terms, strings.termsLine.privacyPolicy)
            val uris = RecordingUris()
            val scene = scene(AccountState(stats = GUEST), language, uris = uris)
            try {
                val shown = scene.everyText()
                val register = shown.indexOf(strings.register)
                assertEquals(register + 1, shown.indexOf(line), "$language: right under Register in $shown")

                scene.links().forEach { tap -> tap() }
            } finally {
                scene.close()
            }
            assertEquals(
                setOf(Site.url(SitePage.TERMS, language), Site.url(SitePage.PRIVACY, language)),
                uris.opened.toSet(),
                "$language",
            )
        }
    }

    /** Log in: the two fields, no rules, the button and the link back. */
    @Test
    fun `the login form has no rules and a link back`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).accountScreens
            val shown = textsOf(AccountState(stats = GUEST, authMode = AuthMode.LOG_IN), language)

            listOf(strings.username, strings.password, strings.logIn, strings.toRegister).forEach { text ->
                assertTrue(text in shown, "$language: \"$text\" is not in $shown")
            }
            listOf(usernameRule(strings), passwordRule(strings), strings.show, strings.register, strings.toLogIn)
                .forEach { text -> assertFalse(text in shown, "$language: \"$text\" is in $shown") }
        }
    }

    @Test
    fun `each link switches the page to the other form`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).accountScreens
            val actions = Recorder()

            tapping(AccountState(stats = GUEST), language, actions) { it.tap(strings.toLogIn) }
            tapping(AccountState(stats = GUEST, authMode = AuthMode.LOG_IN), language, actions) {
                it.tap(strings.toRegister)
            }

            assertEquals(listOf("mode LOG_IN", "mode REGISTER"), actions.calls, "$language")
        }
    }

    @Test
    fun `each form's button sends it and the toggle shows the password`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).accountScreens
            val actions = Recorder()
            val typed = AccountState(stats = GUEST, registerUsername = "bob_1", registerPassword = "correct horse")

            tapping(typed, language, actions) {
                it.tap(strings.show)
                it.tap(strings.register)
            }
            tapping(typed.copy(showRegisterPassword = true), language, actions) { it.tap(strings.hide) }
            tapping(
                AccountState(stats = GUEST, authMode = AuthMode.LOG_IN, loginUsername = "bob_1", loginPassword = "x"),
                language,
                actions,
            ) { it.tap(strings.logIn) }

            assertEquals(listOf("show", "register", "show", "log in"), actions.calls, "$language")
        }
    }

    /** The one warning names the points a login leaves behind, and offers to go ahead or not. */
    @Test
    fun `the warning names the points and offers both ways`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).accountScreens
            val actions = Recorder()
            val warned =
                AccountState(
                    stats = GUEST,
                    authMode = AuthMode.LOG_IN,
                    loginUsername = "bob_1",
                    loginPassword = "correct horse",
                    guestPointsWarning = 12,
                )

            val shown = textsOf(warned, language)
            // The points are a coin and the number, which a screen reader hears in words instead.
            val said = descriptionsOf(warned, language)
            assertTrue(strings.guestPointsWarning.fill(12) in said, "$language: $said")
            assertFalse(strings.logIn in shown, "$language: $shown")
            tapping(warned, language, actions) {
                it.tap(strings.logInAnyway)
                it.tap(stringsOf(language).cancel)
            }

            assertEquals(listOf("log in", "cancel"), actions.calls, "$language")
        }
    }

    /** A refusal shows under the form that sent it, short, and only there. */
    @Test
    fun `a failure shows under the form that sent it`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).accountScreens
            val taken = AccountFailure(AccountAction.REGISTER, DomainError.USERNAME_TAKEN)
            val wrong = AccountFailure(AccountAction.LOG_IN, DomainError.INVALID_LOGIN)

            val logIn = AccountState(stats = GUEST, authMode = AuthMode.LOG_IN)

            assertTrue(strings.usernameTaken in textsOf(AccountState(stats = GUEST, failure = taken), language))
            assertTrue(strings.wrongLogin in textsOf(logIn.copy(failure = wrong), language))
            assertFalse(strings.wrongLogin in textsOf(AccountState(stats = GUEST, failure = wrong), language))
            assertFalse(strings.usernameTaken in textsOf(logIn.copy(failure = taken), language))
        }
    }

    /** Shown before any player is read, a read that failed says so on top, with Try again, and only then. */
    @Test
    fun `a failed read before any shows on top with Try again`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).accountScreens
            val actions = Recorder()
            val failed = AccountState(failure = READ_FAILED)

            listOf(failed, failed.copy(authMode = AuthMode.LOG_IN)).forEach { state ->
                val shown = textsOf(state, language)
                assertEquals(listOf(strings.offline, stringsOf(language).tryAgain), shown.take(2), "$language: $shown")
            }
            tapping(failed, language, actions) { it.tap(stringsOf(language).tryAgain) }
            assertEquals(listOf("refresh"), actions.calls, "$language")

            val read = textsOf(AccountState(stats = GUEST, failure = READ_FAILED), language)
            assertFalse(stringsOf(language).tryAgain in read, "$language: the player is read: $read")
        }
    }

    private fun tapping(
        state: AccountState,
        language: Language,
        actions: Recorder,
        taps: (ImageComposeScene) -> Unit,
    ) {
        val scene = scene(state, language, actions)
        try {
            taps(scene)
        } finally {
            scene.close()
        }
    }

    private fun textsOf(
        state: AccountState,
        language: Language,
    ): List<String> {
        val scene = scene(state, language)
        try {
            return scene.everyText()
        } finally {
            scene.close()
        }
    }

    /** What a screen reader hears for what shows no text of its own: an icon, or the points' coin. */
    private fun descriptionsOf(
        state: AccountState,
        language: Language,
    ): List<String> {
        val scene = scene(state, language)
        try {
            return scene.everyNode().flatMap { it.descriptions }
        } finally {
            scene.close()
        }
    }

    /**
     * On a wide screen, a desktop window less the top bar, the content is a column no wider than
     * `WyrDimens.contentMaxWidth` down the middle, not stretched across (CLAUDE.md §8d, *Wide screens*).
     */
    @Test
    fun `on a wide screen the content is a column down the middle`() {
        STATES.forEach { state ->
            val scene = scene(state, Language.DEFAULT, width = WINDOW_WIDTH, height = WINDOW_HEIGHT)
            try {
                scene.assertInCentredColumn(WINDOW_WIDTH, CONTENT_MAX_WIDTH, "$state")
            } finally {
                scene.close()
            }
        }
    }

    private fun scene(
        state: AccountState,
        language: Language,
        actions: AccountActions = Recorder(),
        dark: Boolean = false,
        width: Int = WIDTH,
        height: Int = HEIGHT,
        uris: RecordingUris = RecordingUris(),
    ): ImageComposeScene =
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            // The page's links open through the test's own handler, never the machine's browser.
            CompositionLocalProvider(LocalUriHandler provides uris) {
                WyrTheme(darkTheme = dark) { WyrStrings(language) { AuthScreen(state = state, actions = actions) } }
            }
        }.also { it.render() }

    /**
     * The taps of every link in a text the scene lays out, in order: a link has no text of its own in
     * the semantics, only a click, and Compose's own marker of a link.
     */
    private fun ImageComposeScene.links(): List<() -> Boolean> =
        everyNode()
            .filter { node -> node.config.any { (key, _) -> key.name == "LinkTestMarker" } }
            .mapNotNull { it.config.getOrNull(SemanticsActions.OnClick)?.action }
            .also { assertEquals(2, it.size, "the terms and the privacy policy") }

    /** What the page asked for, in order. */
    private class Recorder : AccountActions {
        val calls = mutableListOf<String>()

        override fun refresh() {
            calls += "refresh"
        }

        override fun authShown() {
            calls += "shown"
        }

        override fun setAuthMode(mode: AuthMode) {
            calls += "mode $mode"
        }

        override fun leftAuth() {
            calls += "left"
        }

        override fun setRegisterUsername(text: String) = Unit

        override fun setRegisterPassword(text: String) = Unit

        override fun toggleShowRegisterPassword() {
            calls += "show"
        }

        override fun register() {
            calls += "register"
        }

        override fun setLoginUsername(text: String) = Unit

        override fun setLoginPassword(text: String) = Unit

        override fun logIn() {
            calls += "log in"
        }

        override fun cancelLogIn() {
            calls += "cancel"
        }

        override fun logOut() {
            calls += "log out"
        }

        override fun deleteAccount() {
            calls += "delete account"
        }
    }

    private companion object {
        /** A desktop window as Compose first opens one (800 by 600) less the top bar. */
        const val WINDOW_WIDTH = 800
        const val WINDOW_HEIGHT = 552

        /** The widest a screen's content gets (`WyrDimens.contentMaxWidth`). */
        const val CONTENT_MAX_WIDTH = 600

        const val WIDTH = 400
        const val HEIGHT = 900

        /** An iPhone SE (667 high) less its status bar (20) and the top bar above the page (48). */
        const val SHORT_PHONE_WIDTH = 375
        const val SHORT_PHONE_HEIGHT = 599

        val GUEST = PlayerStats(totalPoints = 12, questionsAnswered = 10)

        val READ_FAILED = AccountFailure(AccountAction.LOAD, DomainError.NETWORK)

        val STATES =
            listOf(
                AccountState(stats = GUEST),
                AccountState(),
                AccountState(failure = READ_FAILED),
                AccountState(
                    authMode = AuthMode.LOG_IN,
                    loginUsername = "bob_1",
                    loginPassword = "correct horse",
                    failure = READ_FAILED,
                    running = AccountAction.LOAD,
                ),
                AccountState(stats = GUEST, registerUsername = "bob_1", registerPassword = "correct horse"),
                AccountState(
                    stats = GUEST,
                    registerUsername = "a b",
                    registerPassword = "short",
                    showRegisterPassword = true,
                ),
                AccountState(
                    stats = GUEST,
                    registerUsername = "bob_1",
                    registerPassword = "correct horse",
                    failure = AccountFailure(AccountAction.REGISTER, DomainError.USERNAME_TAKEN),
                ),
                AccountState(
                    stats = GUEST,
                    registerUsername = "bob_1",
                    registerPassword = "correct horse",
                    failure = AccountFailure(AccountAction.REGISTER, DomainError.RATE_LIMITED, 42.seconds),
                    running = AccountAction.REGISTER,
                ),
                AccountState(stats = GUEST, authMode = AuthMode.LOG_IN),
                AccountState(
                    stats = GUEST,
                    authMode = AuthMode.LOG_IN,
                    loginUsername = "bob_1",
                    loginPassword = "wrong horse",
                    failure = AccountFailure(AccountAction.LOG_IN, DomainError.INVALID_LOGIN),
                ),
                AccountState(
                    stats = GUEST,
                    authMode = AuthMode.LOG_IN,
                    loginUsername = "bob_1",
                    loginPassword = "correct horse",
                    guestPointsWarning = 123_456,
                    failure = AccountFailure(AccountAction.LOG_IN, DomainError.RATE_LIMITED, 42.seconds),
                    running = AccountAction.LOG_IN,
                ),
            )
    }
}
