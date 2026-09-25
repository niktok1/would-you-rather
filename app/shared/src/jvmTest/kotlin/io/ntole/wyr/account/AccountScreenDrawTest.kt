package io.ntole.wyr.account

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionRules
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.core.network.environment.WyrEnvironment
import io.ntole.wyr.everyText
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.WyrStrings
import io.ntole.wyr.language.stringsOf
import io.ntole.wyr.nodes
import io.ntole.wyr.tap
import io.ntole.wyr.texts
import io.ntole.wyr.theme.WyrTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The Account screen drawn off screen at two phones' sizes, in each theme and each language, from
 * every state it can be in. Compose measures and draws it all, so a layout that cannot be measured
 * fails here rather than when the screen opens. Whether what it draws fits is asked separately: the
 * screen scrolls, so it draws whatever its height. It is drawn for DEV, whose server line is the
 * longest, unless a test names another environment.
 */
class AccountScreenDrawTest {
    @Test
    fun `the screen draws in every state it can be in`() {
        (GUEST_STATES + NOT_A_GUEST).forEach { state ->
            listOf(false, true).forEach { dark ->
                Language.entries.forEach { language ->
                    draw(state, dark, language, WIDTH, HEIGHT)
                    draw(state, dark, language, SHORT_PHONE_WIDTH, SHORT_PHONE_HEIGHT)
                }
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
     * A LOCAL or DEV build names the server it talks to under everything else the screen shows, in
     * every state; a PROD build names none (CLAUDE.md §8e).
     */
    @Test
    fun `the screen names its server last outside prod and none in prod`() {
        listOf(WyrEnvironment.LOCAL, WyrEnvironment.DEV).forEach { environment ->
            (GUEST_STATES + NOT_A_GUEST).forEach { state ->
                assertEquals(serverLine(environment), textsShown(state, environment).last(), "$environment: $state")
            }
        }
        (GUEST_STATES + NOT_A_GUEST).forEach { state ->
            val shown = textsShown(state, WyrEnvironment.PROD)
            assertTrue(shown.none { it.startsWith("Server") }, "$state shows $shown")
        }
    }

    /**
     * The list under it scrolls, but the language switch, the stats, the guest's one button or Log
     * out, and My questions' heading with New question all show at an iPhone SE's height, before any
     * scrolling, in every language.
     *
     * Measured at the width drawn above, not 375, since CI's Linux fonts wrap wider than a phone's
     * (as `PlayScreenDrawTest` explains).
     */
    @Test
    fun `New question shows before any scrolling`() {
        Language.entries.forEach { language ->
            val newQuestion = stringsOf(language).accountScreens.newQuestion
            (GUEST_STATES + NOT_A_GUEST).filter { it.stats != null }.forEach { state ->
                val scene = scene(state, language)
                try {
                    val button = scene.nodes().single { newQuestion in it.texts }
                    val bottom = button.boundsInRoot.bottom
                    assertTrue(bottom <= SHORT_PHONE_HEIGHT, "$language: $state ends New question at $bottom")
                } finally {
                    scene.close()
                }
            }
        }
    }

    /** My questions: each question the player submitted, newest first, its two options and its status. */
    @Test
    fun `My questions lists each question with its options and status`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).accountScreens
            val scene = scene(AccountState(stats = REGISTERED, submissions = EVERY_STATUS), language)
            try {
                val shown = scene.everyText()
                val expected =
                    EVERY_STATUS.flatMap { listOf(statusText(it, strings), it.optionA, strings.or, it.optionB) }
                assertEquals(expected, shown.filter { it in expected.toSet() }, "$language")
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun `My questions says when there are none and New question opens the form`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).accountScreens
            var opened = 0
            val none = AccountState(stats = GUEST, submissions = emptyList())
            val scene = scene(none, language, onNewQuestion = { opened++ })
            try {
                assertTrue(strings.noQuestions in scene.everyText(), "$language")
                scene.tap(strings.newQuestion)
            } finally {
                scene.close()
            }
            assertEquals(1, opened, "$language")
        }
    }

    @Test
    fun `a list that cannot be read offers to try again`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).accountScreens
            val actions = Refreshes()
            val failure = AccountFailure(AccountAction.LOAD, DomainError.NETWORK)
            val state = AccountState(stats = GUEST, listFailure = failure)
            val scene = scene(state, language, actions = actions)
            try {
                assertTrue(strings.offline in scene.everyText(), "$language")
                scene.tap(strings.tryAgain)
            } finally {
                scene.close()
            }
            assertEquals(1, actions.count, "$language")
        }
    }

    /** With no player read there is nobody's list to show. */
    @Test
    fun `no player read shows no My questions`() {
        val myQuestions = stringsOf(Language.DEFAULT).accountScreens.myQuestions
        NOT_A_GUEST.filter { it.stats == null }.forEach { state ->
            val scene = scene(state, Language.DEFAULT)
            try {
                assertFalse(myQuestions in scene.everyText(), "$state")
            } finally {
                scene.close()
            }
        }
    }

    /** A guest's way to register or log in is one button, to the Auth page; a registered player has none. */
    @Test
    fun `a guest has one button to the Auth page and a registered player none`() {
        Language.entries.forEach { language ->
            val openAuth = stringsOf(language).accountScreens.openAuth
            // Off while an action runs, as every button is.
            GUEST_STATES.filterNot { it.isBusy }.forEach { state ->
                var opened = 0
                val scene = scene(state, language, onOpenAuth = { opened++ })
                try {
                    scene.tap(openAuth)
                } finally {
                    scene.close()
                }
                assertEquals(1, opened, "$language: $state")
            }
            NOT_A_GUEST.forEach { state ->
                val scene = scene(state, language)
                try {
                    assertFalse(openAuth in scene.everyText(), "$language: $state")
                } finally {
                    scene.close()
                }
            }
        }
    }

    private fun draw(
        state: AccountState,
        dark: Boolean,
        language: Language,
        width: Int,
        height: Int,
    ) {
        val scene =
            ImageComposeScene(width = width, height = height, density = Density(1f)) {
                WyrTheme(darkTheme = dark) { WyrStrings(language) { Screen(state, language = language) } }
            }
        try {
            assertEquals(width, scene.render().width)
        } finally {
            scene.close()
        }
    }

    /** Every text [state]'s screen draws, as its semantics hold it, from the top of the screen down. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun textsShown(
        state: AccountState,
        environment: WyrEnvironment = WyrEnvironment.DEV,
    ): List<String> {
        val scene =
            ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
                WyrTheme { Screen(state, environment) }
            }
        try {
            scene.render()
            return scene.semanticsOwners
                .flatMap { owner -> owner.getAllSemanticsNodes(mergingEnabled = false) }
                // Where each is laid out, not where it is clipped to: a guest's forms run past the window.
                .sortedBy { node -> node.positionInRoot.y }
                .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                .map { it.text }
        } finally {
            scene.close()
        }
    }

    private fun scene(
        state: AccountState,
        language: Language,
        onOpenAuth: () -> Unit = {},
        onNewQuestion: () -> Unit = {},
        actions: AccountActions = Refreshes(),
    ): ImageComposeScene =
        ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
            WyrTheme {
                WyrStrings(language) {
                    Screen(
                        state,
                        language = language,
                        onOpenAuth = onOpenAuth,
                        onNewQuestion = onNewQuestion,
                        actions = actions,
                    )
                }
            }
        }.also { it.render() }

    @Composable
    private fun Screen(
        state: AccountState,
        environment: WyrEnvironment = WyrEnvironment.DEV,
        language: Language = Language.DEFAULT,
        onOpenAuth: () -> Unit = {},
        onNewQuestion: () -> Unit = {},
        actions: AccountActions = Refreshes(),
    ) {
        AccountScreen(
            state = state,
            actions = actions,
            environment = environment,
            language = language,
            onSelectLanguage = {},
            onOpenAuth = onOpenAuth,
            onNewQuestion = onNewQuestion,
        )
    }

    /** Counts the reads asked for, and does nothing else. */
    private class Refreshes : AccountActions {
        var count = 0

        override fun refresh() {
            count++
        }

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
    }

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 900

        /** An iPhone SE (667 high) less its status bar (20) and the top bar above the screen (48). */
        const val SHORT_PHONE_WIDTH = 375
        const val SHORT_PHONE_HEIGHT = 599

        val GUEST = PlayerStats(12, 12, 10, 1, 4, 0)

        /** The longest name there can be, and numbers long enough to widen every line. */
        val REGISTERED =
            PlayerStats(123_456, 123_456, 12_345, 1_234, 12_345, 123_456, username = "abcdefghijklmnopqrst")

        val LONGEST = "Be able to fly ".repeat(20).take(SubmissionRules.MAX_OPTION_LENGTH)

        val QUESTION =
            Submission(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = setOf(Category.SUPERPOWERS),
                status = SubmissionStatus.PENDING,
                rejectionReason = null,
                submittedAt = Instant.parse("2026-09-25T12:00:00Z"),
            )

        /** One of every status, newest first, the longest options and reason among them. */
        val EVERY_STATUS =
            listOf(
                QUESTION,
                QUESTION.copy(id = "q2", optionA = "Tea", optionB = "Coffee", status = SubmissionStatus.APPROVED),
                QUESTION.copy(
                    id = "q3",
                    optionA = LONGEST,
                    optionB = LONGEST.reversed(),
                    status = SubmissionStatus.REJECTED,
                    rejectionReason = "x".repeat(200),
                ),
                QUESTION.copy(id = "q4", optionA = "Lie", optionB = "Steal", status = SubmissionStatus.REJECTED),
                QUESTION.copy(id = "q5", optionA = "Run", optionB = "Walk", status = SubmissionStatus.RETIRED),
                QUESTION.copy(id = "q6", optionA = "Sing", optionB = "Dance", status = SubmissionStatus.OTHER),
            )

        /** A guest's screen, its one button to the Auth page under the stats, whatever is typed there. */
        val GUEST_STATES =
            listOf(
                AccountState(stats = GUEST),
                AccountState(stats = GUEST, submissions = emptyList()),
                AccountState(stats = GUEST, submissions = listOf(QUESTION)),
                AccountState(stats = GUEST, listFailure = AccountFailure(AccountAction.LOAD, DomainError.NETWORK)),
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

        /** Every state with no button to the Auth page: no player read yet, and a registered player. */
        val NOT_A_GUEST =
            listOf(
                AccountState(),
                AccountState(running = AccountAction.LOAD),
                AccountState(failure = AccountFailure(AccountAction.LOAD, DomainError.NETWORK)),
                AccountState(stats = REGISTERED),
                AccountState(stats = REGISTERED, submissions = EVERY_STATUS),
                AccountState(stats = REGISTERED, running = AccountAction.LOAD),
                AccountState(stats = REGISTERED, running = AccountAction.LOG_OUT),
                AccountState(stats = REGISTERED, failure = AccountFailure(AccountAction.LOG_OUT, DomainError.NETWORK)),
                AccountState(stats = REGISTERED, failure = AccountFailure(AccountAction.LOAD, DomainError.NETWORK)),
            )
    }
}
