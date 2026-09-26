package io.ntole.wyr

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.ntole.wyr.analytics.RecordingAnalytics
import io.ntole.wyr.core.domain.account.AccountRepository
import io.ntole.wyr.core.domain.account.DeleteAccount
import io.ntole.wyr.core.domain.account.LogIn
import io.ntole.wyr.core.domain.account.LogOut
import io.ntole.wyr.core.domain.account.RegisterAccount
import io.ntole.wyr.core.domain.analytics.Analytics
import io.ntole.wyr.core.domain.analytics.AnalyticsEvent
import io.ntole.wyr.core.domain.analytics.AnalyticsProperty
import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.category.CategoryRepository
import io.ntole.wyr.core.domain.category.GetCategories
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.player.GetPlayerStats
import io.ntole.wyr.core.domain.player.PlayerRepository
import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.domain.question.GetNextQuestion
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.domain.question.SkipQuestion
import io.ntole.wyr.core.domain.reaction.QuestionReactions
import io.ntole.wyr.core.domain.reaction.Reaction
import io.ntole.wyr.core.domain.reaction.ReactionRepository
import io.ntole.wyr.core.domain.reaction.SetReaction
import io.ntole.wyr.core.domain.session.SessionRepository
import io.ntole.wyr.core.domain.submission.GetMySubmissions
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionRepository
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.core.domain.submission.SubmitQuestion
import io.ntole.wyr.core.domain.update.AppUpdate
import io.ntole.wyr.core.domain.vote.AttemptId
import io.ntole.wyr.core.domain.vote.CastVote
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.VoteOutcome
import io.ntole.wyr.core.domain.vote.VoteRepository
import io.ntole.wyr.core.network.InMemoryTokenStorage
import io.ntole.wyr.core.network.TokenStorage
import io.ntole.wyr.core.network.environment.WyrEnvironment
import io.ntole.wyr.di.uiModule
import io.ntole.wyr.language.EnglishStrings
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.LanguageViewModel
import io.ntole.wyr.language.SerbianCyrillicStrings
import io.ntole.wyr.language.categoryName
import io.ntole.wyr.language.fill
import io.ntole.wyr.submit.sendText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

/**
 * The whole app, [App] as every platform shows it, drawn off screen over fakes of the game and driven
 * by tapping its buttons (CLAUDE.md §8d, *Navigation*; §8f). The fakes count what the screens ask for,
 * which says whether a screen's ViewModel lived on while another was shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppNavigationTest {
    private val game = FakeGame()
    private val categories = FakeCategories()
    private val update = FakeUpdate()
    private val analytics = RecordingAnalytics()
    private val storage = InMemoryTokenStorage()
    private val owner = TestOwner()
    private val clock = TestTimeSource()
    private var restored: Map<String, List<Any?>>? = null
    private var saved: Map<String, List<Any?>>? = null

    @BeforeTest
    fun setUp() {
        // viewModelScope runs on Dispatchers.Main, which the JVM has none of under test.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // The clock the analytics time with, which a test moves by hand.
        startKoin { modules(fakes(), uiModule, module { single<TimeSource.WithComparableMarks> { clock } }) }
    }

    @AfterTest
    fun tearDown() {
        owner.viewModelStore.clear()
        stopKoin()
        Dispatchers.resetMain()
    }

    @Test
    fun `the app opens on Home in Serbian Cyrillic`() =
        withApp { scene ->
            assertEquals(listOf(CYRILLIC.gameName, CYRILLIC.play), scene.texts())
            assertEquals(listOf(CYRILLIC.account), scene.descriptions())
            assertEquals(0, game.questionsAsked)
        }

    /** The app opened once, and every screen it shows, each named as it is left (CLAUDE.md §8g). */
    @Test
    fun `every screen shown is reported and each left named`() =
        withApp { scene ->
            scene.tap(CYRILLIC.play)
            scene.tap(CYRILLIC.account)
            scene.tap(CYRILLIC.back)

            val shown = analytics.named(RecordingAnalytics.SCREEN).map { it.properties[RecordingAnalytics.SCREEN_NAME] }
            assertEquals(listOf("home", "play", "account", "play"), shown)
            val left = analytics.named(AnalyticsEvent.SCREEN_LEFT).map { it.properties[AnalyticsProperty.SCREEN] }
            assertEquals(listOf("home", "play", "account"), left)
            val opened = analytics.named(AnalyticsEvent.APP_OPENED).single()
            assertEquals(false, opened.properties[AnalyticsProperty.FROM_BACKGROUND])
            assertEquals(Language.SERBIAN_CYRILLIC.tag, opened.properties[AnalyticsProperty.LANGUAGE])
        }

    /** The player's choice is the analytics' own, which keep it for the device (CLAUDE.md §8g). */
    @Test
    fun `the Statistics switch on Account turns the analytics off and on again`() =
        withApp { scene ->
            val statistics = CYRILLIC.accountScreens.statistics
            scene.tap(CYRILLIC.account)
            assertEquals(ToggleableState.On, scene.toggleOf(statistics))

            scene.tap(statistics)
            assertFalse(analytics.enabled.value)
            assertEquals(ToggleableState.Off, scene.toggleOf(statistics))

            scene.tap(statistics)
            assertTrue(analytics.enabled.value)
            assertEquals(ToggleableState.On, scene.toggleOf(statistics))
        }

    /**
     * An Android rotation makes the composition anew, its saved state restored, while the ViewModels
     * and the analytics live on: the screen it shows is the visit it showed, not opened again, and
     * leaving it and coming back is a visit of its own (CLAUDE.md §8g).
     */
    @Test
    fun `a rotation on Account or Submit reports neither opened again`() {
        game.username = "bob"
        withApp { scene -> scene.tap(CYRILLIC.account) }
        afterRotation { scene ->
            assertTrue(CYRILLIC.accountScreens.newQuestion in scene.texts(), "the Account screen is not shown")
            scene.tap(CYRILLIC.accountScreens.newQuestion)
        }
        assertEquals(1, analytics.named(AnalyticsEvent.ACCOUNT_OPENED).size)

        afterRotation { scene ->
            assertTrue(sendText(CYRILLIC, 1) in scene.descriptions(), "the form is not shown")
            scene.tap(CYRILLIC.back)
        }

        assertEquals(1, analytics.named(AnalyticsEvent.SUBMIT_OPENED).size)
        assertEquals(2, analytics.named(AnalyticsEvent.ACCOUNT_OPENED).size, "back on Account is a visit of its own")
        // Account, Account rotated, the form, the form rotated, and Account again: each reads the points.
        assertEquals(5, game.statsRead)
    }

    /**
     * Once any call is answered that this build is too old, the one screen shown says a new version is
     * available, whatever was shown before (CLAUDE.md §8e, *The build on every request*). The desktop
     * has no store to open, so no button.
     */
    @Test
    fun `once the server refuses the build the app shows only that a new version is available`() =
        withApp { scene ->
            scene.tap(CYRILLIC.play)

            update.required.value = true
            scene.settle()

            assertEquals(listOf(CYRILLIC.updateScreen.newVersion), scene.texts())
            assertEquals(emptyList(), scene.descriptions())
        }

    /**
     * Delete account asks first, then deletes, and the Account screen shows the fresh guest the device
     * plays on as (CLAUDE.md §8a, *Deleting an account*); Cancel deletes nothing.
     */
    @Test
    fun `an account deleted from the Account screen plays on as a guest there`() {
        game.username = "bob"
        withApp { scene ->
            val strings = CYRILLIC.accountScreens
            scene.tap(CYRILLIC.account)

            scene.tap(strings.deleteAccount.button)
            assertTrue(strings.deleteAccount.warning in scene.texts(), "${scene.texts()}")
            scene.tap(CYRILLIC.cancel)
            assertEquals(emptyList(), game.deleted)

            scene.tap(strings.deleteAccount.button)
            scene.tap(strings.deleteAccount.confirm)

            assertEquals(listOf<String?>("bob"), game.deleted)
            assertTrue(strings.guest in scene.texts(), "${scene.texts()}")
            assertTrue(strings.openAuth in scene.texts(), "a guest's one button")
            assertEquals(1, analytics.named(AnalyticsEvent.ACCOUNT_DELETED).size)
        }
    }

    @Test
    fun `Play opens under a bar with home and the account icon`() =
        withApp { scene ->
            scene.tap(CYRILLIC.play)

            assertEquals(listOf(CYRILLIC.home, CYRILLIC.account), scene.descriptions().take(2))
            assertEquals(1, game.questionsAsked)
        }

    /**
     * Skip is in the row between the cards while a question is asked, after the thumbs, and not on the
     * top bar, which holds home, the categories played and the account icon; it goes past the question
     * to the next. The points, a coin and the number, a screen reader hears in words, last, a little
     * lower in the row than the thumbs' touch targets.
     */
    @Test
    fun `Skip between the cards skips the question asked`() {
        game.serving = QUESTION
        withApp { scene ->
            scene.tap(CYRILLIC.play)
            assertEquals(
                listOf(CYRILLIC.home, CYRILLIC.account) +
                    listOf(CYRILLIC.playScreen.like, CYRILLIC.playScreen.dislike, CYRILLIC.playScreen.skip) +
                    CYRILLIC.points.fill(5),
                scene.descriptions(),
            )
            assertTrue(CYRILLIC.allCategories in scene.texts(), "the categories played are on the top bar")

            scene.tap(CYRILLIC.playScreen.skip)

            assertEquals(listOf(QUESTION.id), game.skipped)
            assertEquals(2, game.questionsAsked)
        }
    }

    /** The Play screen's ViewModel is the app's, not the back stack's: its question is still there. */
    @Test
    fun `Play keeps its question through Account and back`() =
        withApp { scene ->
            scene.tap(CYRILLIC.play)
            scene.tap(CYRILLIC.account)
            assertTrue(CYRILLIC.accountScreens.newQuestion in scene.texts(), "the Account screen is not shown")

            scene.tap(CYRILLIC.back)

            assertEquals(listOf(CYRILLIC.home, CYRILLIC.account), scene.descriptions().take(2))
            assertEquals(1, game.questionsAsked)
        }

    /** The points move on the Account and Submit screens, so Play reads them each time it is shown. */
    @Test
    fun `Play reads the points each time it is shown`() =
        withApp { scene ->
            scene.tap(CYRILLIC.play)
            assertEquals(1, game.statsRead)

            scene.tap(CYRILLIC.account)
            scene.tap(CYRILLIC.back)

            // Once for the Account screen, and once more for Play shown again.
            assertEquals(3, game.statsRead)
        }

    /**
     * The time a question was on screen, which a skip reports, counts while Play is shown and the app
     * in the foreground: not the time on Account, nor in the background (CLAUDE.md §8g).
     */
    @Test
    fun `a question's time counts only while Play is shown`() {
        game.serving = QUESTION
        withApp { scene ->
            scene.tap(CYRILLIC.play)
            clock += 2.seconds
            scene.tap(CYRILLIC.account)
            clock += 10.minutes
            scene.tap(CYRILLIC.back)
            clock += 1.seconds
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            clock += 1.hours
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            clock += 500.milliseconds

            scene.tap(CYRILLIC.playScreen.skip)

            val skipped = analytics.named(AnalyticsEvent.QUESTION_SKIPPED).single()
            assertEquals(3_500L, skipped.properties[AnalyticsProperty.DURATION_MS])
        }
    }

    @Test
    fun `Play keeps its question through Home and back`() =
        withApp { scene ->
            scene.tap(CYRILLIC.play)
            scene.tap(CYRILLIC.home)
            assertEquals(listOf(CYRILLIC.gameName, CYRILLIC.play), scene.texts())

            scene.tap(CYRILLIC.play)

            assertEquals(1, game.questionsAsked)
        }

    @Test
    fun `Account opens from Home and its back arrow returns to Home`() =
        withApp { scene ->
            scene.tap(CYRILLIC.account)
            assertEquals(1, game.statsRead)

            scene.tap(CYRILLIC.back)

            assertEquals(listOf(CYRILLIC.gameName, CYRILLIC.play), scene.texts())
        }

    /** The Account screen reads the player and My questions each time it is shown; the form reads the points. */
    @Test
    fun `the Submit form opens from My questions and its back arrow returns to Account`() =
        withApp { scene ->
            // Only a registered player submits.
            game.username = "bob"
            scene.tap(CYRILLIC.account)
            assertEquals(1, game.statsRead)
            assertEquals(1, game.submissionsRead)

            scene.tap(CYRILLIC.accountScreens.newQuestion)
            // Its cost is a coin and the number, which a screen reader hears in words.
            assertTrue(sendText(CYRILLIC, 1) in scene.descriptions(), "the form is not shown")
            assertEquals(listOf(CYRILLIC.back), scene.descriptions().take(1))
            assertEquals(2, game.statsRead)

            scene.tap(CYRILLIC.back)

            assertTrue(CYRILLIC.accountScreens.newQuestion in scene.texts(), "the Account screen is not shown")
            assertEquals(2, game.submissionsRead)
        }

    /** After a question sent, My questions again, which reads the list and lists it. */
    @Test
    fun `a question sent goes back to My questions which lists it`() =
        withApp { scene ->
            game.username = "bob"
            scene.tap(CYRILLIC.account)
            scene.tap(CYRILLIC.accountScreens.newQuestion)
            scene.type(0, "Fly")
            scene.type(1, "Swim")
            scene.tap(categoryName(FOOD, Language.SERBIAN_CYRILLIC))

            scene.tap(sendText(CYRILLIC, 1))
            scene.settle()

            assertEquals(listOf("Fly"), game.sent.map { it.optionA })
            val shown = scene.everyText()
            assertTrue(CYRILLIC.accountScreens.newQuestion in shown, "the Account screen is not shown: $shown")
            val listed = "Fly ${CYRILLIC.accountScreens.or} Swim"
            assertTrue(
                listed in shown && CYRILLIC.accountScreens.pending in shown,
                "the question is not listed: $shown",
            )
            assertEquals(2, game.submissionsRead)
        }

    /** Sent and then left before its answer came: My questions reads the list again once it is stored. */
    @Test
    fun `a question stored after the player went back is listed on My questions`() =
        withApp { scene ->
            val answer = CompletableDeferred<Unit>()
            game.submitWaitsFor = answer
            game.username = "bob"
            scene.tap(CYRILLIC.account)
            scene.tap(CYRILLIC.accountScreens.newQuestion)
            scene.type(0, "Fly")
            scene.type(1, "Swim")
            scene.tap(categoryName(FOOD, Language.SERBIAN_CYRILLIC))
            scene.tap(sendText(CYRILLIC, 1))

            scene.tap(CYRILLIC.back)
            assertEquals(2, game.submissionsRead, "read as the Account screen is shown again")
            answer.complete(Unit)
            scene.settle()

            val shown = scene.everyText()
            assertTrue(CYRILLIC.accountScreens.newQuestion in shown, "the Account screen is not shown: $shown")
            val listed = "Fly ${CYRILLIC.accountScreens.or} Swim"
            assertTrue(
                listed in shown && CYRILLIC.accountScreens.pending in shown,
                "the question is not listed: $shown",
            )
            assertEquals(3, game.submissionsRead)
        }

    @Test
    fun `the Auth page opens from Account and its back arrow returns to Account`() =
        withApp { scene ->
            scene.tap(CYRILLIC.account)
            scene.tap(CYRILLIC.accountScreens.openAuth)
            assertTrue(CYRILLIC.accountScreens.toLogIn in scene.everyText(), "the Auth page is not shown")

            scene.tap(CYRILLIC.back)

            assertTrue(CYRILLIC.accountScreens.openAuth in scene.texts(), "the Account screen is not shown")
        }

    /** After a register that worked, the Account screen again, which reads the player it now names. */
    @Test
    fun `a registration that worked goes back to Account as the account`() =
        withApp { scene ->
            scene.tap(CYRILLIC.account)
            scene.tap(CYRILLIC.accountScreens.openAuth)
            scene.type(0, "bob_1")
            scene.type(1, "correct horse")

            scene.tap(CYRILLIC.accountScreens.register)
            scene.settle()

            assertEquals("bob_1", game.username)
            val shown = scene.everyText()
            assertFalse(CYRILLIC.accountScreens.register in shown, "the Auth page is still shown: $shown")
            assertFalse(CYRILLIC.accountScreens.openAuth in shown, "a registered player has no way to register: $shown")
            assertTrue(shown.any { "bob_1" in it }, "the account is not named: $shown")
        }

    /** A registration whose answer was lost made the account all the same: the page goes back as for one that came. */
    @Test
    fun `a registration whose answer was lost goes back to Account as the account`() =
        withApp { scene ->
            game.registerAnswerLost = true
            scene.tap(CYRILLIC.account)
            scene.tap(CYRILLIC.accountScreens.openAuth)
            scene.type(0, "bob_1")
            scene.type(1, "correct horse")

            scene.tap(CYRILLIC.accountScreens.register)
            scene.settle()

            assertEquals("bob_1", game.username)
            val shown = scene.everyText()
            assertFalse(CYRILLIC.accountScreens.register in shown, "the Auth page is still shown: $shown")
            assertFalse(CYRILLIC.accountScreens.offline in shown, "the account was made: $shown")
            assertTrue(shown.any { "bob_1" in it }, "the account is not named: $shown")
        }

    /**
     * The back stack unwinds a screen at a tap: Auth, then Account, then Home. Android's back goes
     * through the same navigator (`SystemBack`), which binds nothing on the JVM: only `NavigatorTest`
     * and a device run cover it.
     */
    @Test
    fun `the back arrow from the Auth page and then from Account returns to Home`() =
        withApp { scene ->
            scene.tap(CYRILLIC.account)
            scene.tap(CYRILLIC.accountScreens.openAuth)
            scene.tap(CYRILLIC.back)
            scene.tap(CYRILLIC.back)

            assertEquals(listOf(CYRILLIC.gameName, CYRILLIC.play), scene.texts())
        }

    @Test
    fun `the categories open from Play and Play plays what is picked there`() =
        withApp { scene ->
            scene.tap(CYRILLIC.play)
            scene.tap(ALL_PLAYED)
            assertEquals(listOf(CYRILLIC.back), scene.descriptions().take(1))
            assertEquals(1, categories.reads, "read as the screen opens")

            scene.tap("Храна")
            scene.tap("Етика")
            scene.tap(CYRILLIC.play)

            assertEquals(listOf(CYRILLIC.home, CYRILLIC.account), scene.descriptions().take(2))
            assertEquals(listOf(setOf("FOOD", "ETHICS")), game.categoryChanges)
            assertEquals(2, game.questionsAsked, "a question from them")
            assertTrue("Храна, Етика" in scene.texts(), "${scene.texts()}")
        }

    @Test
    fun `back from the categories plays nothing picked there`() =
        withApp { scene ->
            scene.tap(CYRILLIC.play)
            scene.tap(ALL_PLAYED)
            scene.tap("Храна")

            scene.tap(CYRILLIC.back)

            assertEquals(listOf(CYRILLIC.home, CYRILLIC.account), scene.descriptions().take(2))
            assertEquals(emptyList(), game.categoryChanges)
            assertEquals(1, game.questionsAsked)
            scene.tap(ALL_PLAYED)
            assertEquals(ToggleableState.Off, scene.toggleOf("Храна"), "a visit starts afresh")
            assertEquals(ToggleableState.On, scene.toggleOf(CYRILLIC.allCategories))
        }

    /** The menu changes the screen it is on at once, and every screen after it, and is kept. */
    @Test
    fun `the language menu changes every screen at once and is kept`() =
        withApp { scene ->
            scene.tap(CYRILLIC.account)

            scene.tap("${CYRILLIC.language}: ${Language.SERBIAN_CYRILLIC.ownName}")
            scene.tap(Language.ENGLISH.ownName)

            assertTrue(ENGLISH.accountScreens.newQuestion in scene.texts(), "${scene.texts()}")
            assertEquals(listOf(ENGLISH.back), scene.descriptions().take(1))
            scene.tap(ENGLISH.back)
            assertEquals(listOf(ENGLISH.gameName, ENGLISH.play), scene.texts())
            assertEquals("en", storage.read(LanguageViewModel.KEY))
        }

    @Test
    fun `the app opens in the language kept on the device`() {
        runBlocking { storage.write(LanguageViewModel.KEY, Language.ENGLISH.tag) }

        withApp { scene -> assertEquals(listOf(ENGLISH.gameName, ENGLISH.play), scene.texts()) }
    }

    /**
     * Draws [App] as a 375 by 599 phone, a lifecycle and a ViewModel owner of the test's around it, and
     * its saved state restored from [restored], none unless a test sets it. What it saved as the test
     * ended is in [saved].
     */
    private fun withApp(test: (ImageComposeScene) -> Unit) {
        val registry = SaveableStateRegistry(restored) { true }
        val scene =
            ImageComposeScene(width = 375, height = 599, density = Density(1f)) {
                CompositionLocalProvider(
                    LocalLifecycleOwner provides owner,
                    LocalViewModelStoreOwner provides owner,
                    LocalSaveableStateRegistry provides registry,
                ) { App() }
            }
        try {
            scene.settle()
            test(scene)
            // Before the composition goes, as an activity saves its state before it is destroyed.
            saved = registry.performSave()
        } finally {
            scene.close()
        }
    }

    /**
     * An Android rotation, then [test]: [App] drawn anew with the saved state the one before saved, over
     * the same ViewModels, lifecycle and singletons, the analytics among them.
     */
    private fun afterRotation(test: (ImageComposeScene) -> Unit) {
        restored = saved
        withApp(test)
    }

    private fun fakes() =
        module {
            single<TokenStorage> { storage }
            single { WyrEnvironment.PROD }
            single<QuestionRepository> { game }
            single<SessionRepository> { game }
            single<VoteRepository> { game }
            single<ReactionRepository> { game }
            single<PlayerRepository> { game }
            single<AccountRepository> { game }
            single<SubmissionRepository> { game }
            single<CategoryRepository> { categories }
            single<Analytics> { analytics }
            single<AppUpdate> { update }
            factory { GetNextQuestion(questions = get(), session = get()) }
            factory { SkipQuestion(questions = get(), session = get()) }
            factory { CastVote(votes = get(), session = get()) }
            factory { SetReaction(reactions = get(), session = get()) }
            factory { GetPlayerStats(players = get(), session = get()) }
            factory { RegisterAccount(accounts = get(), session = get(), analytics = get()) }
            factory { LogIn(accounts = get(), questions = get(), session = get(), analytics = get()) }
            factory { LogOut(accounts = get(), questions = get(), analytics = get()) }
            factory { DeleteAccount(accounts = get(), questions = get(), analytics = get()) }
            factory { SubmitQuestion(submissions = get(), session = get()) }
            factory { GetMySubmissions(submissions = get(), session = get()) }
            factory { GetCategories(categories = get()) }
        }

    /** Whether the line showing [text] is ticked. */
    private fun ImageComposeScene.toggleOf(text: String): ToggleableState? =
        nodes().single { text in it.texts }.config.getOrNull(SemanticsProperties.ToggleableState)

    /**
     * The server's categories, [LISTED], none read until a screen reads them: the Categories screen's
     * lines and the Submit form's chips. Each read counted.
     */
    private class FakeCategories : CategoryRepository {
        var reads = 0

        override val categories = MutableStateFlow<List<Category>>(emptyList())

        override suspend fun refresh(): List<Category> {
            reads++
            categories.value = LISTED
            return LISTED
        }
    }

    /** Whether the server has refused this build as too old, which a test raises by hand. */
    private class FakeUpdate : AppUpdate {
        override val required = MutableStateFlow(false)
    }

    /** A resumed lifecycle and a ViewModel store, as an activity or a window gives the app. */
    private class TestOwner :
        LifecycleOwner,
        ViewModelStoreOwner {
        override val lifecycle: LifecycleRegistry =
            LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }

        override val viewModelStore: ViewModelStore = ViewModelStore()
    }

    /**
     * The game, counting what the screens ask of it. Out of questions unless it is [serving] one, so
     * the Play screen shows a failure; nothing here votes or reacts, and a registration and a
     * submission always work.
     */
    private class FakeGame :
        QuestionRepository,
        SessionRepository,
        VoteRepository,
        ReactionRepository,
        PlayerRepository,
        AccountRepository,
        SubmissionRepository {
        var questionsAsked = 0
        var statsRead = 0
        var submissionsRead = 0

        /** The question every fetch serves, or none: out of questions, which needs none made. */
        var serving: Question? = null

        val skipped = mutableListOf<String>()

        /** The account the guest registered as, or null while none. */
        var username: String? = null

        /** When set, a registration makes the account and then fails as offline, its answer lost. */
        var registerAnswerLost = false

        /** The questions submitted, newest first. */
        val sent = mutableListOf<Submission>()

        /** When set, a submission waits for it before it is stored. */
        var submitWaitsFor: CompletableDeferred<Unit>? = null

        /** Every change of the categories played, in order. */
        val categoryChanges = mutableListOf<Set<String>>()

        override val categories = MutableStateFlow<Set<String>>(emptySet())

        override suspend fun next(): Question {
            questionsAsked++
            return serving ?: throw WyrException(DomainError.OUT_OF_QUESTIONS)
        }

        override suspend fun prefetch() = Unit

        override suspend fun setCategories(categories: Set<String>) {
            categoryChanges += categories
            this.categories.value = categories
        }

        override suspend fun skip(questionId: String) {
            skipped += questionId
        }

        override suspend fun reset() = Unit

        override suspend fun ensure(): String = "player"

        override suspend fun cast(
            questionId: String,
            side: Side,
            attempt: AttemptId,
        ): VoteOutcome = error("nothing votes here")

        override suspend fun setReaction(
            questionId: String,
            reaction: Reaction,
        ): QuestionReactions = error("nothing reacts here")

        override suspend fun stats(): PlayerStats {
            statsRead++
            return PlayerStats(totalPoints = 5, questionsAnswered = 5, username = username)
        }

        override suspend fun register(
            username: String,
            password: String,
        ): String =
            username.lowercase().also {
                this.username = it
                if (registerAnswerLost) throw WyrException(DomainError.NETWORK)
            }

        override suspend fun logIn(
            username: String,
            password: String,
        ) = error("nothing logs in here")

        override suspend fun logOut() = error("nothing logs out here")

        /** Every account deleted, in order: the player playing, by the username they had. */
        val deleted = mutableListOf<String?>()

        override suspend fun deleteAccount() {
            deleted += username
            username = null
        }

        override suspend fun submit(
            optionA: String,
            optionB: String,
            categories: Set<String>,
        ): Submission {
            submitWaitsFor?.await()
            return Submission(
                id = "q${sent.size + 1}",
                optionA = optionA,
                optionB = optionB,
                categories = categories,
                status = SubmissionStatus.PENDING,
                rejectionReason = null,
                submittedAt = Instant.fromEpochMilliseconds(1_790_000_000_000L),
            ).also { sent.add(0, it) }
        }

        override suspend fun mine(): List<Submission> {
            submissionsRead++
            return sent.toList()
        }
    }

    private companion object {
        val CYRILLIC = SerbianCyrillicStrings
        val ENGLISH = EnglishStrings

        val FOOD = Category(id = "FOOD", nameSr = "Храна", nameEn = "Food")

        val LISTED = listOf(FOOD, Category(id = "ETHICS", nameSr = "Етика", nameEn = "Ethics"))

        val QUESTION = Question(id = "q1", optionA = "Fly", optionB = "Swim", categories = setOf(FOOD.id))

        /** The Play screen's categories, All while none is played: a tap on them opens the Categories screen. */
        val ALL_PLAYED = CYRILLIC.allCategories
    }
}
