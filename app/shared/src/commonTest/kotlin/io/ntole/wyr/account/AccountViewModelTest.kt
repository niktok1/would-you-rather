package io.ntole.wyr.account

import io.ntole.wyr.analytics.RecordingAnalytics
import io.ntole.wyr.analytics.RecordingAnalytics.Recorded
import io.ntole.wyr.core.domain.account.AccountRepository
import io.ntole.wyr.core.domain.account.DeleteAccount
import io.ntole.wyr.core.domain.account.LogIn
import io.ntole.wyr.core.domain.account.LogOut
import io.ntole.wyr.core.domain.account.RegisterAccount
import io.ntole.wyr.core.domain.account.UsernameProblem
import io.ntole.wyr.core.domain.analytics.Analytics
import io.ntole.wyr.core.domain.analytics.AnalyticsEvent
import io.ntole.wyr.core.domain.analytics.AnalyticsProperty
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.player.GetPlayerStats
import io.ntole.wyr.core.domain.player.PlayerRepository
import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.domain.playgames.LinkPlayGames
import io.ntole.wyr.core.domain.playgames.PlayGames
import io.ntole.wyr.core.domain.playgames.PlayGamesRepository
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.domain.session.CurrentSession
import io.ntole.wyr.core.domain.session.SessionRepository
import io.ntole.wyr.core.domain.submission.GetMySubmissions
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionRepository
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.language.EnglishStrings
import io.ntole.wyr.language.SerbianCyrillicStrings
import io.ntole.wyr.language.SerbianLatinStrings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
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
    private val analytics = RecordingAnalytics()
    private val game = FakeGame()
    private val playGames = FakePlayGames()

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

    /**
     * The notice of a decision marks seen only a list of the player playing (CLAUDE.md §8d, *The notice
     * of a decision*), so each list is named for the player it was read for: a first launch's guest, and
     * the player a launch's Play Games sign-in made the device.
     */
    @Test
    fun `a list is named for the player it was read for`() =
        runTest(dispatcher) {
            val viewModel = open()
            assertEquals("guest1", viewModel.state.value.readFor, "the guest the read minted")

            game.player = "p2"
            viewModel.refresh()
            testScheduler.advanceUntilIdle()

            assertEquals("p2", viewModel.state.value.readFor)
        }

    /** A launch's Play Games sign-in, or a dead session replaced, makes the device another player. */
    @Test
    fun `another player playing here is read again with nothing of the one before shown meanwhile`() =
        runTest(dispatcher) {
            game.points = 5
            game.questionsOf["guest1"] = listOf(QUESTION)
            val viewModel = open()
            game.calls.clear()
            game.statsWaitsFor = CompletableDeferred()

            game.player = "p2"
            testScheduler.advanceUntilIdle()

            assertNull(viewModel.state.value.stats, "nothing of the guest's while the player is read")
            assertNull(viewModel.state.value.submissions)
            game.statsWaitsFor?.complete(Unit)
            testScheduler.advanceUntilIdle()
            val state = viewModel.state.value
            assertEquals(listOf("stats", "mine"), game.calls)
            assertEquals(emptyList(), state.submissions, "p2's own")
            assertEquals("p2", state.readFor)
            assertEquals(5, state.stats?.totalPoints)
        }

    /** The screen's own login, logout and sign-in read the player after themselves, and only then. */
    @Test
    fun `the screen's own login reads the player once`() =
        runTest(dispatcher) {
            game.accounts["bob_1"] = "correct horse" to "bob-player"
            val viewModel = open()
            game.calls.clear()

            viewModel.setLoginUsername("bob_1")
            viewModel.setLoginPassword("correct horse")
            viewModel.logIn()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("logIn bob_1", "reset", "stats", "mine"), game.calls)
            assertEquals("bob-player", viewModel.state.value.readFor)
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

            assertEquals("Guest", nameOf(state.shown(), EnglishStrings))
            assertEquals("Гост", nameOf(state.shown(), SerbianCyrillicStrings))
            assertEquals(12, state.shown().totalPoints)
            assertNull(state.failure)
        }

    @Test
    fun `a guest's stats read as the server counted them`() =
        runTest(dispatcher) {
            game.points = 12
            game.questionsAnswered = 10

            val state = open().state.value

            assertEquals("Guest", nameOf(state.shown(), EnglishStrings))
            assertEquals(12, state.shown().totalPoints)
            assertEquals(listOf(StatCell("10", "Questions answered")), statCells(state.shown(), ENGLISH))
            assertEquals(listOf(StatCell("10", "Одговорена питања")), statCells(state.shown(), CYRILLIC))
        }

    @Test
    fun `a registered player's stats read as the server counted them`() =
        runTest(dispatcher) {
            game.accounts["bob_1"] = "correct horse" to "guest1"
            game.points = 1
            game.questionsAnswered = 1

            val state = open().state.value

            assertEquals("bob_1", nameOf(state.shown(), EnglishStrings))
            assertEquals(1, state.shown().totalPoints)
            assertEquals(listOf(StatCell("1", "Questions answered")), statCells(state.shown(), ENGLISH))
        }

    @Test
    fun `each showing reads the stats again`() =
        runTest(dispatcher) {
            val viewModel = open()
            assertEquals(
                StatCell("0", "Questions answered"),
                statCells(viewModel.state.value.shown(), ENGLISH).single(),
            )

            // Played on the Play screen meanwhile: more questions answered.
            game.questionsAnswered = 24
            viewModel.refresh()
            testScheduler.advanceUntilIdle()

            assertEquals(
                StatCell("24", "Questions answered"),
                statCells(viewModel.state.value.shown(), ENGLISH).single(),
            )
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
            assertEquals("bob_1", nameOf(state.shown(), EnglishStrings))
            assertEquals(12, state.stats?.totalPoints)
            assertEquals("guest1", game.player, "the same player")
            // A registered player sees no form, so nothing typed is kept; only the Auth page's cue to go back.
            assertEquals(
                AccountState(stats = state.stats, submissions = emptyList(), readFor = "guest1", signedIn = true),
                state,
            )
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
            assertEquals("Guest", nameOf(state.shown(), EnglishStrings))
        }

    /**
     * A player registered by Play Games alone adds a username on the Auth page's Register form: one
     * refused stays there, with its failure and what was typed, and one that works goes back, named
     * (CLAUDE.md §8a, *Play Games sign-in*).
     */
    @Test
    fun `a player registered by Play Games alone adds a username and a refused one stays on the form`() =
        runTest(dispatcher) {
            game.playGamesLinked = true
            game.accounts["bob_1"] = "their password" to "someone"
            val viewModel = open()
            val linked = viewModel.state.value.shown()
            assertTrue(linked.registered)
            assertEquals("Google Play Games", nameOf(linked, EnglishStrings))

            viewModel.setRegisterUsername("Bob_1")
            viewModel.setRegisterPassword("correct horse")
            viewModel.register()
            testScheduler.advanceUntilIdle()

            val refused = viewModel.state.value
            assertEquals(AccountFailure(AccountAction.REGISTER, DomainError.USERNAME_TAKEN), refused.failure)
            assertFalse(refused.signedIn, "a refusal stays on the form")
            assertEquals("Bob_1", refused.registerUsername)

            viewModel.setRegisterUsername("bob_2")
            viewModel.register()
            testScheduler.advanceUntilIdle()

            val added = viewModel.state.value
            assertTrue(added.signedIn)
            assertNull(added.failure)
            assertEquals("bob_2", nameOf(added.shown(), EnglishStrings))
        }

    /**
     * The card names a player registered by Play Games alone as this device's Play Games names them,
     * read with the stats, and nobody else: a guest's read asks Play Games nothing, and once the player
     * has a username that is their name.
     */
    @Test
    fun `a Play Games player is named as Play Games names them`() =
        runTest(dispatcher) {
            playGames.name = "nikola"
            val guest = open()
            assertNull(guest.state.value.playGamesName, "a guest is nobody's in Play Games")

            game.playGamesLinked = true
            guest.refresh()
            testScheduler.advanceUntilIdle()
            val linked = guest.state.value
            assertEquals("nikola", linked.playGamesName)
            assertEquals("nikola", nameOf(linked.shown(), EnglishStrings, linked.playGamesName))

            playGames.name = null
            guest.refresh()
            testScheduler.advanceUntilIdle()
            val unnamed = guest.state.value
            assertEquals("Google Play Games", nameOf(unnamed.shown(), EnglishStrings, unnamed.playGamesName))

            playGames.name = "nikola"
            guest.setRegisterUsername("bob_2")
            guest.setRegisterPassword("correct horse")
            guest.register()
            testScheduler.advanceUntilIdle()
            val added = guest.state.value
            assertNull(added.playGamesName, "a username is the name now")
            assertEquals("bob_2", nameOf(added.shown(), EnglishStrings, added.playGamesName))
        }

    @Test
    fun `a build without Play Games offers none and signs in with none`() =
        runTest(dispatcher) {
            val viewModel = open()

            assertFalse(viewModel.state.value.offersPlayGames)
            viewModel.signInWithPlayGames()
            testScheduler.advanceUntilIdle()
            assertFalse("playGames code-1" in game.calls)
        }

    /** The no-click way: the guest playing is linked, keeping everything, and the page goes back. */
    @Test
    fun `Play Games signs the guest in and goes back to the Account screen`() =
        runTest(dispatcher) {
            playGames.available = true
            game.points = 5
            val viewModel = open()
            assertTrue(viewModel.state.value.offersPlayGames)

            viewModel.signInWithPlayGames()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(state.signedIn)
            assertNull(state.failure)
            assertTrue(state.shown().registered)
            assertEquals("Google Play Games", nameOf(state.shown(), EnglishStrings))
            assertFalse(state.offersPlayGames, "linked already")
            assertTrue(state.playGamesAvailable, "the build still has it")
            assertTrue("playGames code-1" in game.calls)
        }

    /** A Play Games player linked to another player already: the device is theirs, its queue dropped. */
    @Test
    fun `Play Games signs in as the player it was linked to already`() =
        runTest(dispatcher) {
            playGames.available = true
            game.accounts["bob_1"] = "correct horse" to "bob-player"
            game.playGamesPlayer = "bob-player"
            val viewModel = open()

            viewModel.signInWithPlayGames()
            testScheduler.advanceUntilIdle()

            assertTrue(viewModel.state.value.signedIn)
            assertEquals("bob_1", nameOf(viewModel.state.value.shown(), EnglishStrings))
            assertTrue("reset" in game.calls, "the queue was the guest's")
        }

    /**
     * Another player's from the sign-in on: nothing of the guest's is shown while theirs is read, so
     * the rows the notice marks are never the guest's (CLAUDE.md §8d, *The notice of a decision*).
     */
    @Test
    fun `a Play Games sign-in shows nothing of the guest's while the player's own is read`() =
        runTest(dispatcher) {
            playGames.available = true
            game.questionsOf["guest1"] = listOf(QUESTION)
            game.accounts["bob_1"] = "correct horse" to "bob-player"
            game.playGamesPlayer = "bob-player"
            val viewModel = open()
            assertEquals(listOf(QUESTION), viewModel.state.value.submissions)
            val listRead = CompletableDeferred<Unit>()
            game.mineWaitsFor = listRead

            viewModel.signInWithPlayGames()
            testScheduler.advanceUntilIdle()
            assertEquals(null, viewModel.state.value.submissions, "the guest's list is gone")

            listRead.complete(Unit)
            testScheduler.advanceUntilIdle()
            assertEquals(emptyList(), viewModel.state.value.submissions, "bob's own")
        }

    @Test
    fun `a player who does not sign in to Play Games stays on the page with nothing sent`() =
        runTest(dispatcher) {
            playGames.available = true
            playGames.authenticated = false
            val viewModel = open()

            viewModel.signInWithPlayGames()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertFalse(state.signedIn)
            assertNull(state.failure)
            assertFalse("playGames code-1" in game.calls)
        }

    @Test
    fun `a Play Games sign-in the server refuses says so under the button and is reported`() =
        runTest(dispatcher) {
            playGames.available = true
            game.playGamesRefusedWith = DomainError.PLAY_GAMES_UNAVAILABLE
            val viewModel = open()

            viewModel.signInWithPlayGames()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(AccountFailure(AccountAction.PLAY_GAMES, DomainError.PLAY_GAMES_UNAVAILABLE), state.failure)
            assertEquals(ENGLISH.somethingWrong, failureMessage(assertNotNull(state.failure), ENGLISH))
            assertFalse(state.signedIn)
            val shown = analytics.named(AnalyticsEvent.ERROR_SHOWN).single()
            assertEquals("play_games", shown.properties[AnalyticsProperty.ACTION])
            // The other form takes it down, as it does a form's.
            viewModel.setAuthMode(AuthMode.LOG_IN)
            assertNull(viewModel.state.value.failure)
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
            assertEquals("bob_1", nameOf(state.shown(), EnglishStrings))
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
            assertEquals("bob_1", nameOf(state.shown(), EnglishStrings))
            assertNull(state.guestPointsWarning)
        }

    /** Not a guest: their points stay on the Play Games account, which the Auth page signs in to again. */
    @Test
    fun `a player registered by Play Games alone logs in without a warning`() =
        runTest(dispatcher) {
            game.points = 5
            game.playGamesLinked = true
            game.accounts["bob_1"] = "correct horse" to "bob-player"
            val viewModel = open()
            viewModel.setAuthMode(AuthMode.LOG_IN)
            viewModel.setLoginUsername("bob_1")
            viewModel.setLoginPassword("correct horse")

            viewModel.logIn()
            testScheduler.advanceUntilIdle()

            assertEquals("bob-player", game.player)
            assertNull(viewModel.state.value.guestPointsWarning)
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
            assertEquals("Guest", nameOf(state.shown(), EnglishStrings))
            assertEquals("wrong horse", state.loginPassword, "kept, to put right")
        }

    @Test
    fun `a logout plays on as a fresh guest`() =
        runTest(dispatcher) {
            game.accounts["bob_1"] = "correct horse" to "guest1"
            val viewModel = open()
            assertEquals("bob_1", nameOf(viewModel.state.value.shown(), EnglishStrings))

            viewModel.logOut()
            testScheduler.advanceUntilIdle()

            val stats = viewModel.state.value.shown()
            assertEquals("Guest", nameOf(stats, EnglishStrings))
            assertEquals("guest2", game.player)
        }

    /** Deleted, for a guest and a registered player alike: the screen then shows the fresh guest. */
    @Test
    fun `a deletion plays on as a fresh guest on the same screen`() =
        runTest(dispatcher) {
            game.accounts["bob_1"] = "correct horse" to "guest1"
            game.questionsOf["guest1"] = listOf(QUESTION)
            val viewModel = open()
            game.calls.clear()

            viewModel.deleteAccount()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("deleteAccount", "reset", "stats", "mine"), game.calls)
            val state = viewModel.state.value
            assertEquals("Guest", nameOf(state.shown(), EnglishStrings))
            assertEquals("guest2", game.player)
            assertEquals(emptyList(), state.submissions, "the fresh guest's none")
            assertNull(state.failure)
        }

    /** Offline, or anything else: said so, and the player shown is the one still here. */
    @Test
    fun `a deletion that failed says so and forgets nothing`() =
        runTest(dispatcher) {
            game.accounts["bob_1"] = "correct horse" to "guest1"
            game.deleteFailsWith = DomainError.NETWORK
            val viewModel = open()

            viewModel.deleteAccount()
            testScheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(AccountFailure(AccountAction.DELETE, DomainError.NETWORK), state.failure)
            assertEquals("bob_1", nameOf(state.shown(), EnglishStrings))
            assertEquals("guest1", game.player)
            assertEquals(
                listOf(mapOf(AnalyticsProperty.CODE to "NETWORK", AnalyticsProperty.ACTION to "delete_account")),
                analytics.named(AnalyticsEvent.ERROR_SHOWN).map { it.properties },
            )
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
            assertEquals("Guest", nameOf(state.shown(), EnglishStrings))
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
            assertEquals("bob_1", nameOf(viewModel.state.value.shown(), EnglishStrings))

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
            assertEquals("bob_1", nameOf(state.shown(), EnglishStrings))
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
            assertEquals("Guest", nameOf(viewModel.state.value.shown(), EnglishStrings))

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

    /** Every showing of the screen, from which the funnel to an account starts (CLAUDE.md §8g). */
    @Test
    fun `each showing of the screen is reported and reads the player`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.shown()
            testScheduler.advanceUntilIdle()
            viewModel.shown()
            testScheduler.advanceUntilIdle()

            assertEquals(2, analytics.named(AnalyticsEvent.ACCOUNT_OPENED).size)
            assertEquals(2, game.calls.count { it == "stats" })
        }

    /** A rotation's composition shows the visit it showed: read again, and not reported again. */
    @Test
    fun `a showing that begins no visit reads the player and reports nothing`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.shown(newVisit = false)
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList(), analytics.named(AnalyticsEvent.ACCOUNT_OPENED))
            assertEquals(1, game.calls.count { it == "stats" })
        }

    @Test
    fun `a registration is reported as sent and as completed`() =
        runTest(dispatcher) {
            val viewModel = open()
            viewModel.setRegisterUsername("Bob_1")
            viewModel.setRegisterPassword("correct horse")

            viewModel.register()
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf(AnalyticsEvent.REGISTER_STARTED, AnalyticsEvent.REGISTER_COMPLETED),
                analytics.events.map { it.name },
            )
        }

    /** Its answer lost, the account it made is read after it all the same: it completed. */
    @Test
    fun `a registration whose answer was lost is reported as completed`() =
        runTest(dispatcher) {
            game.registerAnswerLost = true
            val viewModel = open()
            viewModel.setRegisterUsername("bob_1")
            viewModel.setRegisterPassword("correct horse")

            viewModel.register()
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf(AnalyticsEvent.REGISTER_STARTED, AnalyticsEvent.REGISTER_COMPLETED),
                analytics.events.map { it.name },
            )
        }

    /** Never what was typed: only the failure's code, and what failed. */
    @Test
    fun `a refused registration is reported as started and its failure as shown`() =
        runTest(dispatcher) {
            game.accounts["bob_1"] = "correct horse" to "someone"
            val viewModel = open()
            viewModel.setRegisterUsername("bob_1")
            viewModel.setRegisterPassword("horse battery")

            viewModel.register()
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf(
                    Recorded(AnalyticsEvent.REGISTER_STARTED),
                    Recorded(
                        AnalyticsEvent.ERROR_SHOWN,
                        mapOf(AnalyticsProperty.CODE to "USERNAME_TAKEN", AnalyticsProperty.ACTION to "register"),
                    ),
                ),
                analytics.events,
            )
            assertTrue(analytics.recorded.none { "bob_1" in it.toString() || "horse" in it.toString() })
        }

    /**
     * The Auth page takes signed in down as it leaves, which it does as soon as a frame shows the
     * registration worked, while the read after it may still run, and fail.
     */
    @Test
    fun `a registration is reported as completed although the Auth page left and the read after failed`() =
        runTest(dispatcher) {
            val viewModel = open()
            viewModel.setRegisterUsername("bob_1")
            viewModel.setRegisterPassword("correct horse")
            val read = CompletableDeferred<Unit>()
            game.statsWaitsFor = read

            viewModel.register()
            testScheduler.advanceUntilIdle()
            viewModel.leftAuth()
            game.statsFailWith = DomainError.NETWORK
            read.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertEquals(1, analytics.named(AnalyticsEvent.REGISTER_COMPLETED).size)
        }

    /** A read quick enough that the Auth page leaves only while My questions is read. */
    @Test
    fun `a login is reported as completed although the Auth page left while the list was read`() =
        runTest(dispatcher) {
            game.accounts["bob_1"] = "correct horse" to "bob-player"
            val viewModel = open()
            viewModel.setAuthMode(AuthMode.LOG_IN)
            viewModel.setLoginUsername("bob_1")
            viewModel.setLoginPassword("correct horse")
            val list = CompletableDeferred<Unit>()
            game.mineWaitsFor = list

            viewModel.logIn()
            testScheduler.advanceUntilIdle()
            viewModel.leftAuth()
            list.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertEquals(1, analytics.named(AnalyticsEvent.LOGIN_COMPLETED).size)
        }

    @Test
    fun `a login is reported once it worked`() =
        runTest(dispatcher) {
            game.accounts["bob_1"] = "correct horse" to "bob-player"
            val viewModel = open()
            viewModel.setAuthMode(AuthMode.LOG_IN)
            viewModel.setLoginUsername("bob_1")

            viewModel.setLoginPassword("wrong horse")
            viewModel.logIn()
            testScheduler.advanceUntilIdle()
            assertEquals(emptyList(), analytics.named(AnalyticsEvent.LOGIN_COMPLETED))

            viewModel.setLoginPassword("correct horse")
            viewModel.logIn()
            testScheduler.advanceUntilIdle()

            assertEquals(1, analytics.named(AnalyticsEvent.LOGIN_COMPLETED).size)
            val shown = analytics.named(AnalyticsEvent.ERROR_SHOWN).single()
            assertEquals(
                mapOf(AnalyticsProperty.CODE to "INVALID_LOGIN", AnalyticsProperty.ACTION to "log_in"),
                shown.properties,
            )
        }

    @Test
    fun `a logout is reported`() =
        runTest(dispatcher) {
            val viewModel = open()

            viewModel.logOut()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf(Recorded(AnalyticsEvent.LOGOUT)), analytics.events)
        }

    @Test
    fun `a read that failed is reported as shown and a list that failed too`() =
        runTest(dispatcher) {
            game.mineFailsWith = DomainError.SERVER
            open()

            assertEquals(
                listOf(mapOf(AnalyticsProperty.CODE to "SERVER", AnalyticsProperty.ACTION to "my_questions")),
                analytics.named(AnalyticsEvent.ERROR_SHOWN).map { it.properties },
            )

            analytics.recorded.clear()
            game.statsFailWith = DomainError.NETWORK
            val viewModel = viewModel()
            viewModel.refresh()
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf("account", "my_questions"),
                analytics.named(AnalyticsEvent.ERROR_SHOWN).map { it.properties[AnalyticsProperty.ACTION] },
            )
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
            registerAccount = RegisterAccount(game, game, Analytics.None),
            logInToAccount = LogIn(game, game, game, Analytics.None),
            logOutOfAccount = LogOut(game, game, Analytics.None),
            deleteTheAccount = DeleteAccount(game, game, Analytics.None),
            analytics = analytics,
            linkPlayGames = LinkPlayGames(playGames, game, game, game, Analytics.None),
            session = game,
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
        QuestionRepository,
        CurrentSession,
        PlayGamesRepository {
        val calls = mutableListOf<String>()

        /** When set, a Play Games sign-in is refused with it. */
        var playGamesRefusedWith: DomainError? = null

        /** The player a Play Games sign-in makes the device, when not the one playing. */
        var playGamesPlayer: String? = null

        /** Each account's password and player, by username, lower-cased. */
        val accounts = mutableMapOf<String, Pair<String, String>>()

        /** The stats of whoever is playing, as the server counts them. */
        var points = 0
        var questionsAnswered = 0

        /** Whether whoever is playing signed in with Play Games (CLAUDE.md §8a). */
        var playGamesLinked = false
        var statsFailWith: DomainError? = null

        /** When set, a read of the stats waits for it before it answers, or fails with [statsFailWith]. */
        var statsWaitsFor: CompletableDeferred<Unit>? = null

        /** When set, a registration waits for it before it answers. */
        var registerWaitsFor: CompletableDeferred<Unit>? = null

        /** When set, a registration makes the account and then fails as offline, its answer lost. */
        var registerAnswerLost = false

        /** The questions each player submitted, by player; none for a player not named. */
        val questionsOf = mutableMapOf<String, List<Submission>>()
        var mineFailsWith: DomainError? = null

        /** When set, a read of the player's questions waits for it before it answers. */
        var mineWaitsFor: CompletableDeferred<Unit>? = null

        private val stored = MutableStateFlow<String?>(null)

        /**
         * Who is playing on this device, as the stored session names them, or none. A test sets it for
         * what makes the device another player with nothing asked of the screen: a launch's Play Games
         * sign-in, or a dead session replaced.
         */
        var player: String?
            get() = stored.value
            set(value) {
                stored.value = value
            }
        private var guestsMinted = 0

        override suspend fun ensure(): String = player ?: "guest${++guestsMinted}".also { player = it }

        override fun current(): String? = player

        override val sessions: Flow<String> = stored.filterNotNull()

        override fun isSettled(): Boolean = false

        override suspend fun signIn(serverAuthCode: String): String? {
            calls += "playGames $serverAuthCode"
            playGamesRefusedWith?.let { throw WyrException(it) }
            playGamesPlayer?.let { player = it }
            playGamesLinked = true
            return ensure()
        }

        override suspend fun stats(): PlayerStats {
            calls += "stats"
            statsWaitsFor?.await()
            statsFailWith?.let { throw WyrException(it) }
            val playing = ensure()
            return PlayerStats(
                totalPoints = points,
                questionsAnswered = questionsAnswered,
                username = accounts.entries.firstOrNull { it.value.second == playing }?.key,
                playGamesLinked = playGamesLinked,
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

        /** When set, a deletion fails with it and forgets nothing. */
        var deleteFailsWith: DomainError? = null

        override suspend fun deleteAccount() {
            calls += "deleteAccount"
            deleteFailsWith?.let { throw WyrException(it) }
            val deleted = player
            accounts.values.removeAll { it.second == deleted }
            questionsOf.remove(deleted)
            player = null
        }

        override suspend fun mine(): List<Submission> {
            calls += "mine"
            mineWaitsFor?.await()
            mineFailsWith?.let { throw WyrException(it) }
            return questionsOf[ensure()].orEmpty()
        }

        override suspend fun submit(
            optionA: String,
            optionB: String,
            categories: Set<String>,
        ): Submission = error("the Account screen submits nothing: the Submit screen does")

        override val categories: StateFlow<Set<String>> = MutableStateFlow(emptySet())

        override suspend fun next(): Question = error("the Account screen serves no question")

        override suspend fun prefetch() = Unit

        override suspend fun setCategories(categories: Set<String>) = Unit

        override suspend fun skip(questionId: String) = Unit

        override suspend fun reset() {
            calls += "reset"
        }
    }

    /**
     * Play Games on a build that has it, the player signed in to it, once a test says the build has it:
     * none by default, as every build without its ids.
     */
    private class FakePlayGames : PlayGames {
        override var available = false
        var authenticated = true
        var signsIn = false
        var name: String? = null

        override suspend fun isAuthenticated(): Boolean = authenticated

        override suspend fun signIn(): Boolean = signsIn.also { authenticated = it }

        override suspend fun serverAuthCode(): String = "code-1"

        override suspend fun playerName(): String? = name
    }

    private companion object {
        val ENGLISH = EnglishStrings.accountScreens
        val CYRILLIC = SerbianCyrillicStrings.accountScreens

        val QUESTION =
            Submission(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = setOf("SUPERPOWERS"),
                status = SubmissionStatus.PENDING,
                rejectionReason = null,
                submittedAt = Instant.fromEpochMilliseconds(1_790_000_000_000L),
            )
    }
}
