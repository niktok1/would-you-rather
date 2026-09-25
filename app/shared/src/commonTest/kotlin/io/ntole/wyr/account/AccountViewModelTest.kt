package io.ntole.wyr.account

import io.ntole.wyr.core.domain.account.AccountRepository
import io.ntole.wyr.core.domain.account.LogIn
import io.ntole.wyr.core.domain.account.LogOut
import io.ntole.wyr.core.domain.account.RegisterAccount
import io.ntole.wyr.core.domain.account.UsernameProblem
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.player.GetPlayerStats
import io.ntole.wyr.core.domain.player.PlayerRepository
import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.domain.session.SessionRepository
import io.ntole.wyr.core.domain.submission.GetMySubmissions
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionRepository
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.language.EnglishStrings
import io.ntole.wyr.language.SerbianCyrillicStrings
import io.ntole.wyr.language.SerbianLatinStrings
import io.ntole.wyr.language.pointsText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val game = FakeGame()

    @BeforeTest
    fun setUp() {
        // viewModelScope runs on Dispatchers.Main, which has no implementation under test.
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `nothing is read until the screen asks`() =
        runTest(dispatcher) {
            viewModel()
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList(), game.calls)
        }

    @Test
    fun `showing the screen reads the player and then their questions`() =
        runTest(dispatcher) {
            game.questionsOf["guest1"] = listOf(QUESTION)

            val state = open().state.value

            assertEquals(listOf("stats", "mine"), game.calls)
            assertEquals(listOf(QUESTION), state.submissions)
            assertNull(state.listFailure)
        }

    /** My questions are the player's: another player's after a login, and a fresh guest's none. */
    @Test
    fun `a login shows the account's questions and a logout none`() =
        runTest(dispatcher) {
            val theirs = QUESTION.copy(id = "q2", optionA = "Tea", optionB = "Coffee")
            game.questionsOf["guest1"] = listOf(QUESTION)
            game.questionsOf["bob-player"] = listOf(theirs)
            game.accounts["bob_1"] = "correct horse" to "bob-player"
            val viewModel = open()

            viewModel.setLoginUsername("bob_1")
            viewModel.setLoginPassword("correct horse")
            viewModel.logIn()
            testScheduler.advanceUntilIdle()
            assertEquals(listOf(theirs), viewModel.state.value.submissions)

            viewModel.logOut()
            testScheduler.advanceUntilIdle()
            assertEquals(emptyList(), viewModel.state.value.submissions)
        }

    @Test
    fun `every action reads the list again`() =
        runTest(dispatcher) {
            val viewModel = open()
            game.calls.clear()
            viewModel.setRegisterUsername("bob_1")
            viewModel.setRegisterPassword("correct horse")

            viewModel.register()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("register bob_1", "stats", "mine"), game.calls)
        }

    @Test
    fun `a list that cannot be read says so under it and keeps the stats`() =
        runTest(dispatcher) {
            game.points = 3
            game.mineFailsWith = DomainError.NETWORK
            val viewModel = open()

            val state = viewModel.state.value
            assertEquals(3, state.stats?.totalPoints)
            assertNull(state.submissions)
            assertEquals(AccountFailure(AccountAction.LOAD, DomainError.NETWORK), state.listFailure)
            assertNull(state.failure, "the stats were read")

            game.mineFailsWith = null
            viewModel.refresh()
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList(), viewModel.state.value.submissions)
            assertNull(viewModel.state.value.listFailure)
        }

    @Test
    fun `a guest reads as a guest with their points`() =
        runTest(dispatcher) {
            game.points = 12

            val state = open().state.value

            assertEquals("Guest", nameOf(state.shown(), ENGLISH))
            assertEquals("Гост", nameOf(state.shown(), CYRILLIC))
            assertEquals("12 P", pointsText(state.shown().totalPoints))
            assertNull(state.failure)
        }

    @Test
    fun `a guest's stats read as the server counted them`() =
        runTest(dispatcher) {
            game.points = 12
            game.answersGiven = 15
            game.questionsAnswered = 10
            game.cycle = 2
            game.dueThisCycle = 4
            game.likesReceived = 3

            val state = open().state.value

            assertEquals("Guest", nameOf(state.shown(), ENGLISH))
            assertEquals(12, state.shown().totalPoints)
            assertEquals(
                listOf(
                    StatCell("15", "Answers"),
                    StatCell("10", "Questions"),
                    StatCell("2", "Cycle", "4 left"),
                    StatCell("3", "Likes"),
                ),
                statCells(state.shown(), ENGLISH),
            )
            assertEquals(
                listOf(
                    StatCell("15", "Одговори"),
                    StatCell("10", "Питања"),
                    StatCell("2", "Циклус", "још 4"),
                    StatCell("3", "Лајкови"),
                ),
                statCells(state.shown(), CYRILLIC),
            )
        }

    @Test
    fun `a registered player's stats read as the server counted them`() =
        runTest(dispatcher) {
            game.accounts["bob_1"] = "correct horse" to "guest1"
            game.points = 1
            game.answersGiven = 1
            game.questionsAnswered = 1
            game.cycle = 1
            game.dueThisCycle = 1
            game.likesReceived = 1

            val state = open().state.value

            assertEquals("bob_1", nameOf(state.shown(), ENGLISH))
            assertEquals("1 P", pointsText(state.shown().totalPoints))
            assertEquals(
                listOf(
                    StatCell("1", "Answers"),
                    StatCell("1", "Questions"),
                    StatCell("1", "Cycle", "1 left"),
                    StatCell("1", "Likes"),
                ),
                statCells(state.shown(), ENGLISH),
            )
        }

    @Test
    fun `each showing reads the stats again`() =
        runTest(dispatcher) {
            val viewModel = open()
            assertEquals(StatCell("1", "Cycle", "0 left"), statCells(viewModel.state.value.shown(), ENGLISH)[2])

            // Played on the Play screen meanwhile: the last question of cycle 1 answered, then more asked for.
            game.cycle = 2
            game.dueThisCycle = 24
            viewModel.refresh()
            testScheduler.advanceUntilIdle()

            assertEquals(StatCell("2", "Cycle", "24 left"), statCells(viewModel.state.value.shown(), ENGLISH)[2])
        }

    @Test
    fun `a registration keeps the points and reads as logged in`() =
        runTest(dispatcher) {
            game.points = 12
            val viewModel = open()

            viewModel.setRegisterUsername("Bob_1")
            viewModel.setRegisterPassword("correct horse")
            viewModel.register()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("bob_1", nameOf(state.shown(), ENGLISH))
            assertEquals(12, state.stats?.totalPoints)
            assertEquals("guest1", game.player, "the same player")
            // A registered player sees no form, so nothing typed is kept; only the Auth page's cue to go back.
            assertEquals(AccountState(stats = state.stats, submissions = emptyList(), signedIn = true), state)
            assertTrue("register Bob_1" in game.calls)
        }

    @Test
    fun `the form says what the rules refuse and sends nothing`() =
        runTest(dispatcher) {
            val viewModel = open()
            game.calls.clear()

            viewModel.setRegisterUsername("a b")
            viewModel.setRegisterPassword("correct horse")
            viewModel.register()
            testScheduler.advanceUntilIdle()

            assertEquals(UsernameProblem.INVALID_CHARACTER, viewModel.state.value.usernameProblem)
            assertFalse(viewModel.state.value.canRegister)
            assertEquals(emptyList(), game.calls)
        }

    @Test
    fun `an empty form names no problem and cannot be sent`() =
        runTest(dispatcher) {
            val state = open().state.value

            assertNull(state.usernameProblem)
            assertNull(state.passwordProblem)
            assertFalse(state.canRegister)
            assertFalse(state.canLogIn)
        }

    @Test
    fun `a taken username shows under the register form and keeps what was typed`() =
        runTest(dispatcher) {
            game.accounts["bob_1"] = "their password" to "someone"
            val viewModel = open()

            viewModel.setRegisterUsername("Bob_1")
            viewModel.setRegisterPassword("correct horse")
            viewModel.register()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(AccountFailure(AccountAction.REGISTER, DomainError.USERNAME_TAKEN), state.failure)
            assertEquals("That name is taken.", failureMessage(assertNotNull(state.failure), ENGLISH))
            assertEquals("Bob_1", state.registerUsername)
            assertEquals("correct horse", state.registerPassword)
            assertEquals("Guest", nameOf(state.shown(), ENGLISH))
        }

    @Test
    fun `a guest with points is warned once and the next Log in goes ahead`() =
        runTest(dispatcher) {
            game.points = 5
            game.accounts["bob_1"] = "correct horse" to "bob-player"
            val viewModel = open()
            game.calls.clear()
            viewModel.setLoginUsername("bob_1")
            viewModel.setLoginPassword("correct horse")

            viewModel.logIn()
            testScheduler.advanceUntilIdle()

            assertEquals(5, viewModel.state.value.guestPointsWarning)
            assertEquals(emptyList(), game.calls, "nothing sent before the warning")

            viewModel.logIn()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue("logIn bob_1" in game.calls)
            assertEquals("bob-player", game.player)
            assertEquals("bob_1", nameOf(state.shown(), ENGLISH))
            assertNull(state.guestPointsWarning)
            assertEquals("", state.loginPassword)
        }

    @Test
    fun `a guest with no points logs in without a warning`() =
        runTest(dispatcher) {
            game.accounts["bob_1"] = "correct horse" to "bob-player"
            val viewModel = open()
            viewModel.setLoginUsername("bob_1")
            viewModel.setLoginPassword("correct horse")

            viewModel.logIn()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("bob-player", game.player)
            assertEquals("bob_1", nameOf(state.shown(), ENGLISH))
            assertNull(state.guestPointsWarning)
        }

    @Test
    fun `cancelling the warning logs in to nothing`() =
        runTest(dispatcher) {
            game.points = 5
            val viewModel = open()
            game.calls.clear()
            viewModel.setLoginUsername("bob_1")
            viewModel.setLoginPassword("correct horse")
            viewModel.logIn()

            viewModel.cancelLogIn()
            testScheduler.advanceUntilIdle()

            assertNull(viewModel.state.value.guestPointsWarning)
            assertEquals(emptyList(), game.calls)
        }

    @Test
    fun `a wrong login shows under the login form and the guest plays on`() =
        runTest(dispatcher) {
            game.accounts["bob_1"] = "correct horse" to "bob-player"
            val viewModel = open()
            viewModel.setLoginUsername("bob_1")
            viewModel.setLoginPassword("wrong horse")

            viewModel.logIn()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(AccountFailure(AccountAction.LOG_IN, DomainError.INVALID_LOGIN), state.failure)
            assertEquals("guest1", game.player)
            assertEquals("Guest", nameOf(state.shown(), ENGLISH))
            assertEquals("wrong horse", state.loginPassword, "kept, to put right")
        }

    @Test
    fun `a logout plays on as a fresh guest`() =
        runTest(dispatcher) {
            game.accounts["bob_1"] = "correct horse" to "guest1"
            val viewModel = open()
            assertEquals("bob_1", nameOf(viewModel.state.value.shown(), ENGLISH))

            viewModel.logOut()
            testScheduler.advanceUntilIdle()

            val stats = viewModel.state.value.shown()
            assertEquals("Guest", nameOf(stats, ENGLISH))
            assertEquals("guest2", game.player)
        }

    @Test
    fun `a read that fails offers to try again and a second read works`() =
        runTest(dispatcher) {
            game.statsFailWith = DomainError.NETWORK
            val viewModel = open()

            assertNull(viewModel.state.value.stats)
            assertEquals(AccountFailure(AccountAction.LOAD, DomainError.NETWORK), viewModel.state.value.failure)

            game.statsFailWith = null
            viewModel.refresh()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("Guest", nameOf(state.shown(), ENGLISH))
            assertNull(state.failure)
        }

    @Test
    fun `a second action while one runs is ignored`() =
        runTest(dispatcher) {
            val viewModel = open()
            game.calls.clear()
            viewModel.setRegisterUsername("bob_1")
            viewModel.setRegisterPassword("correct horse")

            viewModel.register()
            viewModel.register()
            testScheduler.advanceUntilIdle()

            assertEquals(1, game.calls.count { it.startsWith("register") })
        }

    @Test
    fun `each failure reads as what the player can do about it`() {
        assertEquals(
            "Wrong username or password.",
            failureMessage(AccountFailure(AccountAction.LOG_IN, DomainError.INVALID_LOGIN), ENGLISH),
        )
        assertEquals(
            "Too many tries. Wait 42 s.",
            failureMessage(
                AccountFailure(AccountAction.LOG_IN, DomainError.RATE_LIMITED, retryAfter = 42.seconds),
                ENGLISH,
            ),
        )
        assertEquals(
            "Too many tries. Wait a moment.",
            failureMessage(AccountFailure(AccountAction.REGISTER, DomainError.RATE_LIMITED), ENGLISH),
        )
        assertEquals(
            "No connection. Check your internet.",
            failureMessage(AccountFailure(AccountAction.LOAD, DomainError.NETWORK), ENGLISH),
        )
        assertEquals(
            "Превише покушаја. Сачекај 42 сек.",
            failureMessage(
                AccountFailure(AccountAction.LOG_IN, DomainError.RATE_LIMITED, retryAfter = 42.seconds),
                CYRILLIC,
            ),
        )
        // Not "Нема везе.", which reads first as "never mind".
        assertEquals(
            "Нема интернет везе.",
            failureMessage(AccountFailure(AccountAction.LOAD, DomainError.NETWORK), CYRILLIC),
        )
        assertEquals(
            "Nema internet veze.",
            failureMessage(AccountFailure(AccountAction.LOAD, DomainError.NETWORK), SerbianLatinStrings.accountScreens),
        )
        // The player's own account, not a name someone else holds.
        assertEquals(
            "Већ имаш налог.",
            failureMessage(AccountFailure(AccountAction.REGISTER, DomainError.ALREADY_REGISTERED), CYRILLIC),
        )
    }

    @Test
    fun `the rules read with their numbers in every language`() {
        assertEquals("3–20 characters: a–z, 0–9, _", usernameRule(ENGLISH))
        assertEquals("6–128 characters", passwordRule(ENGLISH))
        assertEquals("3–20 знакова: a–z, 0–9, _", usernameRule(CYRILLIC))
        assertEquals("6–128 znakova", passwordRule(SerbianLatinStrings.accountScreens))
    }

    @Test
    fun `the Auth page opens on Register and switches to Log in and back`() =
        runTest(dispatcher) {
            val viewModel = open()
            assertEquals(AuthMode.REGISTER, viewModel.state.value.authMode)

            viewModel.setAuthMode(AuthMode.LOG_IN)
            assertEquals(AuthMode.LOG_IN, viewModel.state.value.authMode)

            viewModel.setAuthMode(AuthMode.REGISTER)
            assertEquals(AuthMode.REGISTER, viewModel.state.value.authMode)
        }

    @Test
    fun `the other form takes the warning down`() =
        runTest(dispatcher) {
            game.points = 5
            val viewModel = open()
            viewModel.setAuthMode(AuthMode.LOG_IN)
            viewModel.setLoginUsername("bob_1")
            viewModel.setLoginPassword("correct horse")
            viewModel.logIn()
            assertEquals(5, viewModel.state.value.guestPointsWarning)

            viewModel.setAuthMode(AuthMode.REGISTER)

            assertNull(viewModel.state.value.guestPointsWarning)
            assertEquals("bob_1", viewModel.state.value.loginUsername, "what was typed stays")
        }

    @Test
    fun `the other form takes a form's failure down and keeps a read's`() =
        runTest(dispatcher) {
            game.accounts["bob_1"] = "their password" to "someone"
            val viewModel = open()
            viewModel.setRegisterUsername("bob_1")
            viewModel.setRegisterPassword("correct horse")
            viewModel.register()
            testScheduler.advanceUntilIdle()
            assertEquals(
                DomainError.USERNAME_TAKEN,
                viewModel.state.value.failure
                    ?.error,
            )

            viewModel.setAuthMode(AuthMode.LOG_IN)
            assertNull(viewModel.state.value.failure)

            game.statsFailWith = DomainError.NETWORK
            viewModel.refresh()
            testScheduler.advanceUntilIdle()
            viewModel.setAuthMode(AuthMode.REGISTER)
            assertEquals(AccountFailure(AccountAction.LOAD, DomainError.NETWORK), viewModel.state.value.failure)
        }

    @Test
    fun `the forms do not switch while an action runs`() =
        runTest(dispatcher) {
            val viewModel = open()
            val answer = CompletableDeferred<Unit>()
            game.registerWaitsFor = answer
            viewModel.setRegisterUsername("bob_1")
            viewModel.setRegisterPassword("correct horse")
            viewModel.register()
            testScheduler.advanceUntilIdle()

            viewModel.setAuthMode(AuthMode.LOG_IN)
            assertEquals(AuthMode.REGISTER, viewModel.state.value.authMode)

            answer.complete(Unit)
            testScheduler.advanceUntilIdle()
        }

    @Test
    fun `a registration that worked is signed in until the Auth page leaves`() =
        runTest(dispatcher) {
            val viewModel = open()
            assertFalse(viewModel.state.value.signedIn)
            viewModel.setRegisterUsername("bob_1")
            viewModel.setRegisterPassword("correct horse")

            viewModel.register()
            testScheduler.advanceUntilIdle()

            // Up through the read after it, which empties the forms of a registered player.
            assertTrue(viewModel.state.value.signedIn)
            assertEquals("bob_1", nameOf(viewModel.state.value.shown(), ENGLISH))

            viewModel.leftAuth()
            assertFalse(viewModel.state.value.signedIn)
        }

    /** The account was made, so the Auth page goes back as for an answer that came, with no failure. */
    @Test
    fun `a registration whose answer was lost is signed in as the account it made`() =
        runTest(dispatcher) {
            game.registerAnswerLost = true
            val viewModel = open()
            viewModel.setRegisterUsername("bob_1")
            viewModel.setRegisterPassword("correct horse")

            viewModel.register()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(state.signedIn)
            assertEquals("bob_1", nameOf(state.shown(), ENGLISH))
            assertNull(state.failure)
        }

    @Test
    fun `a login that worked is signed in`() =
        runTest(dispatcher) {
            game.accounts["bob_1"] = "correct horse" to "bob-player"
            val viewModel = open()
            viewModel.setAuthMode(AuthMode.LOG_IN)
            viewModel.setLoginUsername("bob_1")
            viewModel.setLoginPassword("correct horse")

            viewModel.logIn()
            testScheduler.advanceUntilIdle()

            assertTrue(viewModel.state.value.signedIn)
            assertEquals(AuthMode.REGISTER, viewModel.state.value.authMode, "the forms are gone with the guest")
        }

    @Test
    fun `a refused registration or login is not signed in`() =
        runTest(dispatcher) {
            game.accounts["bob_1"] = "correct horse" to "someone"
            val viewModel = open()
            viewModel.setRegisterUsername("bob_1")
            viewModel.setRegisterPassword("correct horse")
            viewModel.register()
            testScheduler.advanceUntilIdle()
            assertFalse(viewModel.state.value.signedIn)

            viewModel.setAuthMode(AuthMode.LOG_IN)
            viewModel.setLoginUsername("bob_1")
            viewModel.setLoginPassword("wrong horse")
            viewModel.logIn()
            testScheduler.advanceUntilIdle()
            assertFalse(viewModel.state.value.signedIn)
        }

    /** A page the player left before its answer came is not sent back later. */
    @Test
    fun `the next action takes signed in down`() =
        runTest(dispatcher) {
            val viewModel = open()
            viewModel.setRegisterUsername("bob_1")
            viewModel.setRegisterPassword("correct horse")
            viewModel.register()
            testScheduler.advanceUntilIdle()

            viewModel.refresh()

            assertFalse(viewModel.state.value.signedIn)
        }

    @Test
    fun `the Auth page reads the player only when none is read`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.authShown()
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("stats", "mine"), game.calls)
            assertEquals("Guest", nameOf(viewModel.state.value.shown(), ENGLISH))

            viewModel.authShown()
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("stats", "mine"), game.calls)
        }

    /** The one warning names the guest's points, so a login waits until they are read. */
    @Test
    fun `Log in waits for a player read`() =
        runTest(dispatcher) {
            game.points = 5
            game.statsFailWith = DomainError.NETWORK
            game.accounts["bob_1"] = "correct horse" to "bob-player"
            val viewModel = viewModel()
            viewModel.authShown()
            testScheduler.advanceUntilIdle()
            viewModel.setAuthMode(AuthMode.LOG_IN)
            viewModel.setLoginUsername("bob_1")
            viewModel.setLoginPassword("correct horse")
            assertEquals(AccountFailure(AccountAction.LOAD, DomainError.NETWORK), viewModel.state.value.failure)
            game.calls.clear()

            assertFalse(viewModel.state.value.canLogIn)
            viewModel.logIn()
            testScheduler.advanceUntilIdle()
            assertEquals(emptyList(), game.calls, "nothing sent before the player is read")

            game.statsFailWith = null
            viewModel.refresh()
            testScheduler.advanceUntilIdle()
            viewModel.logIn()
            assertEquals(5, viewModel.state.value.guestPointsWarning)
        }

    /** The player the screen shows, which a test expects there to be. */
    private fun AccountState.shown(): PlayerStats = assertNotNull(stats, "no player read")

    /** A view model whose screen has been shown, and asked for the player. */
    private fun TestScope.open(): AccountViewModel =
        viewModel().also {
            it.refresh()
            testScheduler.advanceUntilIdle()
        }

    private fun viewModel(): AccountViewModel =
        AccountViewModel(
            getPlayerStats = GetPlayerStats(game, game),
            getMySubmissions = GetMySubmissions(game, game),
            registerAccount = RegisterAccount(game, game),
            logInToAccount = LogIn(game, game),
            logOutOfAccount = LogOut(game, game),
        )

    /**
     * The server and this device's session in one: a player id stored or none, the accounts, and the
     * points of the player playing. Every call that reaches it is in [calls], a password never.
     */
    private class FakeGame :
        PlayerRepository,
        SessionRepository,
        AccountRepository,
        SubmissionRepository,
        QuestionRepository {
        val calls = mutableListOf<String>()

        /** Each account's password and player, by username, lower-cased. */
        val accounts = mutableMapOf<String, Pair<String, String>>()

        /** The stats of whoever is playing, as the server counts them. */
        var points = 0
        var answersGiven = 0
        var questionsAnswered = 0
        var cycle = 1
        var dueThisCycle = 0
        var likesReceived = 0
        var statsFailWith: DomainError? = null

        /** When set, a registration waits for it before it answers. */
        var registerWaitsFor: CompletableDeferred<Unit>? = null

        /** When set, a registration makes the account and then fails as offline, its answer lost. */
        var registerAnswerLost = false

        /** The questions each player submitted, by player; none for a player not named. */
        val questionsOf = mutableMapOf<String, List<Submission>>()
        var mineFailsWith: DomainError? = null

        /** Who is playing on this device, as the stored session names them, or none. */
        var player: String? = null
            private set
        private var guestsMinted = 0

        override suspend fun ensure(): String = player ?: "guest${++guestsMinted}".also { player = it }

        override suspend fun stats(): PlayerStats {
            calls += "stats"
            statsFailWith?.let { throw WyrException(it) }
            val playing = ensure()
            return PlayerStats(
                totalPoints = points,
                answersGiven = answersGiven,
                questionsAnswered = questionsAnswered,
                cycle = cycle,
                dueThisCycle = dueThisCycle,
                likesReceived = likesReceived,
                username = accounts.entries.firstOrNull { it.value.second == playing }?.key,
            )
        }

        override suspend fun register(
            username: String,
            password: String,
        ): String {
            calls += "register $username"
            registerWaitsFor?.await()
            val name = username.lowercase()
            if (name in accounts) throw WyrException(DomainError.USERNAME_TAKEN)
            accounts[name] = password to ensure()
            if (registerAnswerLost) throw WyrException(DomainError.NETWORK)
            return name
        }

        override suspend fun logIn(
            username: String,
            password: String,
        ) {
            calls += "logIn $username"
            val account = accounts[username.lowercase()]
            if (account?.first != password) throw WyrException(DomainError.INVALID_LOGIN)
            player = account.second
        }

        override suspend fun logOut() {
            calls += "logOut"
            player = null
        }

        override suspend fun mine(): List<Submission> {
            calls += "mine"
            mineFailsWith?.let { throw WyrException(it) }
            return questionsOf[ensure()].orEmpty()
        }

        override suspend fun submit(
            optionA: String,
            optionB: String,
            categories: Set<Category>,
        ): Submission = error("the Account screen submits nothing: the Submit screen does")

        override val categories: StateFlow<Set<Category>> = MutableStateFlow(emptySet())

        override suspend fun next(): Question = error("the Account screen serves no question")

        override suspend fun prefetch() = Unit

        override suspend fun setCategories(categories: Set<Category>) = Unit

        override suspend fun skip(questionId: String) = Unit

        override suspend fun reset() {
            calls += "reset"
        }
    }

    private companion object {
        val ENGLISH = EnglishStrings.accountScreens
        val CYRILLIC = SerbianCyrillicStrings.accountScreens

        val QUESTION =
            Submission(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = setOf(Category.SUPERPOWERS),
                status = SubmissionStatus.PENDING,
                rejectionReason = null,
                submittedAt = Instant.fromEpochMilliseconds(1_790_000_000_000L),
            )
    }
}
