package io.ntole.wyr.account

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import io.ntole.wyr.assertInCentredColumn
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionRules
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.core.network.environment.WyrEnvironment
import io.ntole.wyr.descriptions
import io.ntole.wyr.everyNode
import io.ntole.wyr.everyText
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.WyrStrings
import io.ntole.wyr.language.fill
import io.ntole.wyr.language.stringsOf
import io.ntole.wyr.nodes
import io.ntole.wyr.sizeNeeded
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
 * The Account screen (CLAUDE.md §8d, *The Account screen*) drawn off screen at two phones' sizes, in
 * each theme and each language, from every state it can be in, and read and tapped through its
 * semantics. Compose measures and draws it all, so a layout that cannot be measured fails here
 * rather than when the screen opens. Whether what it draws fits is asked separately: the screen
 * scrolls, so it draws whatever its height. It is drawn for DEV, whose server line is the longest,
 * unless a test names another environment.
 */
class AccountScreenDrawTest {
    @Test
    fun `the screen draws in every state it can be in`() {
        (GUEST_STATES + NOT_A_GUEST).forEach { state ->
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
     * The name, or Гост, the points as a coin and the number, which a screen reader hears in words, and
     * each stat as a number over a word or two.
     */
    @Test
    fun `the screen shows who is playing and their points and every stat`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).accountScreens
            listOf(GUEST, REGISTERED).forEach { stats ->
                val state = AccountState(stats = stats, submissions = emptyList())
                val shown = textsOf(state, language)
                val expected =
                    listOf(nameOf(stats, strings)) + statCells(stats, strings).flatMap { listOf(it.value, it.label) }
                expected.forEach { text -> assertTrue(text in shown, "$language: \"$text\" is not in $shown") }
                val said = descriptionsOf(state, language)
                val points = stringsOf(language).points.fill(stats.totalPoints)
                assertTrue(points in said, "$language: \"$points\" is not in $said")
            }
        }
    }

    /** The card shows the questions answered, and no longer the answers given, the cycle or the likes. */
    @Test
    fun `the card's one stat is the questions answered`() {
        assertEquals(
            listOf(StatCell("10", "Questions answered")),
            statCells(GUEST, stringsOf(Language.ENGLISH).accountScreens),
        )
        assertEquals(
            listOf(StatCell("12345", "Одговорена питања")),
            statCells(REGISTERED, stringsOf(Language.SERBIAN_CYRILLIC).accountScreens),
        )
    }

    /**
     * The user's order: who is playing and the stats, a guest's button to the Auth page, My
     * questions, a guest told to register first, the table, the language menu, Log out, and the
     * server line last.
     */
    @Test
    fun `the screen is in the user's order`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).accountScreens
            val menu = language.ownName
            val question = optionsOf(QUESTION, language)

            val guest = textsOf(AccountState(stats = GUEST, submissions = listOf(QUESTION)), language)
            assertInOrder(
                guest,
                listOf(strings.guest, strings.questionsAnswered, strings.openAuth, strings.myQuestions) +
                    listOf(strings.registerToSubmit, strings.question, question, strings.total, menu) +
                    listOf(serverLine(DEV, strings)),
                "$language, a guest",
            )

            val registered = textsOf(AccountState(stats = REGISTERED, submissions = listOf(QUESTION)), language)
            assertInOrder(
                registered,
                listOf(nameOf(REGISTERED, strings), strings.questionsAnswered, strings.myQuestions, question) +
                    listOf(menu, strings.logOut, serverLine(DEV, strings)),
                "$language, a registered player",
            )
            assertFalse(strings.registerToSubmit in registered, "$language: a registered player submits")
        }
    }

    /**
     * A LOCAL or DEV build names the server it talks to under everything else the screen shows, in
     * every state and language; a PROD build names none (CLAUDE.md §8e).
     */
    @Test
    fun `the screen names its server last outside prod and none in prod`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).accountScreens
            listOf(WyrEnvironment.LOCAL, DEV).forEach { environment ->
                (GUEST_STATES + NOT_A_GUEST).forEach { state ->
                    val shown = textsOf(state, language, environment)
                    assertEquals(serverLine(environment, strings), shown.last(), "$language, $environment: $state")
                }
            }
            val prefix = strings.serverLine.substringBefore("{0}")
            (GUEST_STATES + NOT_A_GUEST).forEach { state ->
                val shown = textsOf(state, language, WyrEnvironment.PROD)
                assertTrue(shown.none { it.startsWith(prefix) }, "$language: $state shows $shown")
            }
        }
    }

    /**
     * With no question listed yet, every state needs no scrolling: the card of the player's stats with
     * a guest's button, My questions' heading and its table, the language menu, Log out and the server
     * line all show at an iPhone SE's height, in every language. A list scrolls, under New question
     * (below).
     *
     * Measured at the width drawn above, not 375, since CI's Linux fonts wrap wider than a phone's
     * (as `PlayScreenDrawTest` explains), and for DEV, whose server line is the longest.
     */
    @Test
    fun `every state with no question listed fits a short phone whole`() {
        Language.entries.forEach { language ->
            (GUEST_STATES + NOT_A_GUEST).filter { it.submissions.isNullOrEmpty() }.forEach { state ->
                val (_, height) =
                    sizeNeeded(WIDTH, SHORT_PHONE_HEIGHT) {
                        WyrTheme { WyrStrings(language) { Screen(state, language) } }
                    }
                assertTrue(height <= SHORT_PHONE_HEIGHT, "$language: $state needs $height of $SHORT_PHONE_HEIGHT")
            }
        }
    }

    /**
     * However long the list, the player's stats and My questions' heading with New question show at an
     * iPhone SE's height before any scrolling, in every language.
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
                assertFalse(openAuth in textsOf(state, language), "$language: $state")
            }
        }
    }

    /** A registered player logs out with one button, and a guest, who has nothing to log out of, has none. */
    @Test
    fun `a registered player has Log out and a guest none`() {
        Language.entries.forEach { language ->
            val logOut = stringsOf(language).accountScreens.logOut
            val actions = Recorder()
            val scene = scene(AccountState(stats = REGISTERED, submissions = emptyList()), language, actions = actions)
            try {
                scene.tap(logOut)
            } finally {
                scene.close()
            }
            assertEquals(listOf("log out"), actions.calls, "$language")
            GUEST_STATES.forEach { state -> assertFalse(logOut in textsOf(state, language), "$language: $state") }
        }
    }

    @Test
    fun `a read that fails offers to try again`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).accountScreens
            val actions = Recorder()
            val failed = AccountState(failure = AccountFailure(AccountAction.LOAD, DomainError.NETWORK))
            val scene = scene(failed, language, actions = actions)
            try {
                assertTrue(strings.offline in scene.everyText(), "$language")
                scene.tap(stringsOf(language).tryAgain)
            } finally {
                scene.close()
            }
            assertEquals(listOf("refresh"), actions.calls, "$language")
        }
    }

    /** My questions: a row for each question the player submitted, newest first, its options and its status. */
    @Test
    fun `My questions lists each question with its options and status`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).accountScreens
            val shown = textsOf(AccountState(stats = REGISTERED, submissions = EVERY_STATUS), language)
            val expected = EVERY_STATUS.flatMap { listOf(optionsOf(it, language), statusText(it, strings)) }
            assertEquals(expected, shown.filter { it in expected.toSet() }, "$language")
        }
    }

    /**
     * Each question's likes, dislikes and players who answered it, a served one's as the server counted
     * them and a question never served, pending or rejected, a dash; then a last row adding them up.
     */
    @Test
    fun `the table counts each question and adds them up`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).accountScreens
            val shown = textsOf(AccountState(stats = REGISTERED, submissions = COUNTED), language)

            // Approved 5, 1, 34; retired 2, 0, 9; pending, never served: dashes. Added up: 7, 1, 43.
            val numbers = shown.filter { it.all(Char::isDigit) || it == NOT_SERVED }
            val counted = listOf("5", "1", "34", "2", "0", "9", NOT_SERVED, NOT_SERVED, NOT_SERVED, "7", "1", "43")
            assertEquals(counted, numbers.takeLast(counted.size), "$language: $shown")
            assertInOrder(shown, listOf(strings.question, strings.total), "$language")
        }
    }

    /** A screen reader hears each number with its column's name, and a question never served has none. */
    @Test
    fun `a screen reader hears each number with its column`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).accountScreens
            val said = descriptionsOf(AccountState(stats = REGISTERED, submissions = COUNTED), language)

            listOf(strings.likes, strings.dislikes, strings.answers).forEach { column ->
                assertTrue(column in said, "$language: the heading of $column")
            }
            listOf("${strings.likes}: 5", "${strings.dislikes}: 1", "${strings.answers}: 34", "${strings.answers}: 43")
                .forEach { value -> assertTrue(value in said, "$language: \"$value\" is not in $said") }
        }
    }

    @Test
    fun `an empty table invites the first question and New question opens the form`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).accountScreens
            listOf(strings.newQuestion, strings.firstQuestion).forEach { button ->
                var opened = 0
                val none = AccountState(stats = REGISTERED, submissions = emptyList())
                val scene = scene(none, language, onNewQuestion = { opened++ })
                try {
                    assertTrue(strings.question in scene.everyText(), "$language: the table stays, empty")
                    scene.tap(button)
                } finally {
                    scene.close()
                }
                assertEquals(1, opened, "$language: $button")
            }
        }
    }

    /** Only a registered player submits: a guest is told to register first, and New question is off. */
    @Test
    fun `a guest cannot open the form and is told to register first`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).accountScreens
            var opened = 0
            val none = AccountState(stats = GUEST, submissions = emptyList())
            val scene = scene(none, language, onNewQuestion = { opened++ })
            try {
                val shown = scene.everyText()
                assertEquals(1, shown.count { it == strings.registerToSubmit }, "$language: in the empty table: $shown")
                assertTrue(strings.question in shown, "$language: the table stays, empty")
                assertFalse(strings.firstQuestion in shown, "$language: no way to the form in the table")
                val newQuestion = scene.nodes().single { strings.newQuestion in it.texts }
                assertTrue(newQuestion.config.contains(SemanticsProperties.Disabled), "$language: New question is off")
            } finally {
                scene.close()
            }
            assertEquals(0, opened, "$language")
        }
    }

    @Test
    fun `a list that cannot be read offers to try again`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).accountScreens
            val actions = Recorder()
            val failure = AccountFailure(AccountAction.LOAD, DomainError.NETWORK)
            val scene = scene(AccountState(stats = GUEST, listFailure = failure), language, actions = actions)
            try {
                assertTrue(strings.offline in scene.everyText(), "$language")
                scene.tap(stringsOf(language).tryAgain)
            } finally {
                scene.close()
            }
            assertEquals(listOf("refresh"), actions.calls, "$language")
        }
    }

    /** A read that failed whole says so once, above, where its Try again reads both again. */
    @Test
    fun `a whole read that failed says so once`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).accountScreens
            val failure = AccountFailure(AccountAction.LOAD, DomainError.NETWORK)
            val shown = textsOf(AccountState(stats = GUEST, failure = failure, listFailure = failure), language)

            assertEquals(1, shown.count { it == strings.offline }, "$language: $shown")
            assertEquals(1, shown.count { it == stringsOf(language).tryAgain }, "$language: $shown")
        }
    }

    /** With no player read there is nobody's list to show. */
    @Test
    fun `no player read shows no My questions`() {
        val myQuestions = stringsOf(Language.DEFAULT).accountScreens.myQuestions
        NOT_A_GUEST.filter { it.stats == null }.forEach { state ->
            assertFalse(myQuestions in textsOf(state, Language.DEFAULT), "$state")
        }
    }

    private fun assertInOrder(
        shown: List<String>,
        expected: List<String?>,
        message: String,
    ) {
        val places = expected.map { text -> shown.indexOf(text) }
        assertTrue(places.none { it < 0 } && places == places.sorted(), "$message: $expected in $shown")
    }

    /** A question's options as its row shows them: one, *или* in the language shown, and the other. */
    private fun optionsOf(
        submission: Submission,
        language: Language,
    ): String = "${submission.optionA} ${stringsOf(language).accountScreens.or} ${submission.optionB}"

    /** What a screen reader hears for what shows no text of its own: an icon, a coin, a table's number. */
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

    /** Every text [state]'s screen lays out, from the top down, a list running past the window's included. */
    private fun textsOf(
        state: AccountState,
        language: Language,
        environment: WyrEnvironment = DEV,
    ): List<String> {
        val scene = scene(state, language, environment = environment)
        try {
            return scene.everyText()
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
        // A guest's language menu spans the column; a registered player's shares its row with Log out,
        // so nothing of theirs does, and the guest's shows the column is as wide as it should be.
        listOf(
            AccountState(stats = GUEST, submissions = listOf(QUESTION)) to true,
            AccountState(stats = REGISTERED, submissions = listOf(QUESTION)) to false,
        ).forEach { (state, spanned) ->
            val scene = scene(state, Language.DEFAULT, width = WINDOW_WIDTH, height = WINDOW_HEIGHT)
            try {
                scene.assertInCentredColumn(WINDOW_WIDTH, CONTENT_MAX_WIDTH, "$state", spanned = spanned)
            } finally {
                scene.close()
            }
        }
    }

    private fun scene(
        state: AccountState,
        language: Language,
        environment: WyrEnvironment = DEV,
        onOpenAuth: () -> Unit = {},
        onNewQuestion: () -> Unit = {},
        actions: AccountActions = Recorder(),
        dark: Boolean = false,
        width: Int = WIDTH,
        height: Int = HEIGHT,
    ): ImageComposeScene =
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            WyrTheme(darkTheme = dark) {
                WyrStrings(language) {
                    Screen(state, language, environment, onOpenAuth, onNewQuestion, actions)
                }
            }
        }.also { it.render() }

    @Composable
    private fun Screen(
        state: AccountState,
        language: Language,
        environment: WyrEnvironment = DEV,
        onOpenAuth: () -> Unit = {},
        onNewQuestion: () -> Unit = {},
        actions: AccountActions = Recorder(),
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

    /** What the screen asked for, in order. */
    private class Recorder : AccountActions {
        val calls = mutableListOf<String>()

        override fun refresh() {
            calls += "refresh"
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

        override fun logOut() {
            calls += "log out"
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

        /** An iPhone SE (667 high) less its status bar (20) and the top bar above the screen (48). */
        const val SHORT_PHONE_WIDTH = 375
        const val SHORT_PHONE_HEIGHT = 599

        val DEV = WyrEnvironment.DEV

        val GUEST = PlayerStats(totalPoints = 12, questionsAnswered = 10)

        /** The longest name there can be, and numbers long enough to widen every stat. */
        val REGISTERED =
            PlayerStats(totalPoints = 123_456, questionsAnswered = 12_345, username = "abcdefghijklmnopqrst")

        val LONGEST = "Be able to fly ".repeat(20).take(SubmissionRules.MAX_OPTION_LENGTH)

        val QUESTION =
            Submission(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = setOf("SUPERPOWERS"),
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

        /** Questions whose counts differ, an approved one, a retired one and one never served: pending. */
        val COUNTED =
            listOf(
                QUESTION.copy(
                    id = "c1",
                    status = SubmissionStatus.APPROVED,
                    likeCount = 5,
                    dislikeCount = 1,
                    answerCount = 34,
                ),
                QUESTION.copy(
                    id = "c2",
                    status = SubmissionStatus.RETIRED,
                    likeCount = 2,
                    dislikeCount = 0,
                    answerCount = 9,
                ),
                QUESTION.copy(id = "c3", status = SubmissionStatus.PENDING),
            )

        /** A guest's screen, its one button to the Auth page on the card, whatever is typed there. */
        val GUEST_STATES =
            listOf(
                AccountState(stats = GUEST),
                AccountState(stats = GUEST, submissions = emptyList()),
                AccountState(stats = GUEST, submissions = listOf(QUESTION)),
                AccountState(stats = GUEST, submissions = EVERY_STATUS),
                AccountState(stats = GUEST, listFailure = AccountFailure(AccountAction.LOAD, DomainError.NETWORK)),
                AccountState(
                    stats = GUEST.copy(totalPoints = 1, questionsAnswered = 1),
                    submissions = emptyList(),
                ),
                AccountState(
                    stats = GUEST,
                    submissions = emptyList(),
                    registerUsername = "a b",
                    registerPassword = "short",
                    showRegisterPassword = true,
                    loginUsername = "bob_1",
                    loginPassword = "correct horse",
                    guestPointsWarning = 12,
                    failure = AccountFailure(AccountAction.LOG_IN, DomainError.RATE_LIMITED, 42.seconds),
                    running = AccountAction.LOG_IN,
                ),
                AccountState(
                    stats = GUEST,
                    submissions = emptyList(),
                    failure = AccountFailure(AccountAction.LOAD, DomainError.NETWORK),
                ),
            )

        /** Every state with no button to the Auth page: no player read yet, and a registered player. */
        val NOT_A_GUEST =
            listOf(
                AccountState(),
                AccountState(running = AccountAction.LOAD),
                AccountState(failure = AccountFailure(AccountAction.LOAD, DomainError.NETWORK)),
                AccountState(stats = REGISTERED),
                AccountState(stats = REGISTERED, submissions = emptyList()),
                AccountState(stats = REGISTERED, submissions = EVERY_STATUS),
                AccountState(stats = REGISTERED, submissions = emptyList(), running = AccountAction.LOAD),
                AccountState(stats = REGISTERED, submissions = emptyList(), running = AccountAction.LOG_OUT),
                AccountState(
                    stats = REGISTERED,
                    submissions = emptyList(),
                    failure = AccountFailure(AccountAction.LOG_OUT, DomainError.NETWORK),
                ),
                AccountState(
                    stats = REGISTERED,
                    submissions = emptyList(),
                    failure = AccountFailure(AccountAction.LOAD, DomainError.NETWORK),
                ),
                AccountState(
                    stats = REGISTERED,
                    failure = AccountFailure(AccountAction.LOAD, DomainError.NETWORK),
                    listFailure = AccountFailure(AccountAction.LOAD, DomainError.NETWORK),
                ),
            )
    }
}
