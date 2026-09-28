package io.ntole.wyr

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.SemanticsActions
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
import io.ntole.wyr.about.AppVersion
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
import io.ntole.wyr.core.domain.home.GetHomePicks
import io.ntole.wyr.core.domain.home.HomePickRepository
import io.ntole.wyr.core.domain.home.PickOnHome
import io.ntole.wyr.core.domain.notice.DecisionNotices
import io.ntole.wyr.core.domain.notice.SeenDecisions
import io.ntole.wyr.core.domain.notice.SeenDecisionsStore
import io.ntole.wyr.core.domain.player.GetPlayerStats
import io.ntole.wyr.core.domain.player.PlayerRepository
import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.domain.playgames.LinkPlayGames
import io.ntole.wyr.core.domain.playgames.PlayGames
import io.ntole.wyr.core.domain.playgames.PlayGamesRepository
import io.ntole.wyr.core.domain.push.DevicePush
import io.ntole.wyr.core.domain.push.KeepPushTokenRegistered
import io.ntole.wyr.core.domain.push.PushPlatform
import io.ntole.wyr.core.domain.push.PushTokenRepository
import io.ntole.wyr.core.domain.question.GetNextQuestion
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.domain.question.SkipQuestion
import io.ntole.wyr.core.domain.reaction.QuestionReactions
import io.ntole.wyr.core.domain.reaction.Reaction
import io.ntole.wyr.core.domain.reaction.ReactionRepository
import io.ntole.wyr.core.domain.reaction.SetReaction
import io.ntole.wyr.core.domain.report.HideAuthor
import io.ntole.wyr.core.domain.report.HideQuestion
import io.ntole.wyr.core.domain.report.ReportQuestion
import io.ntole.wyr.core.domain.report.ReportReason
import io.ntole.wyr.core.domain.report.ReportRepository
import io.ntole.wyr.core.domain.session.CurrentSession
import io.ntole.wyr.core.domain.session.SessionRepository
import io.ntole.wyr.core.domain.shop.BuyTheme
import io.ntole.wyr.core.domain.shop.GetShop
import io.ntole.wyr.core.domain.shop.Shop
import io.ntole.wyr.core.domain.shop.ShopRepository
import io.ntole.wyr.core.domain.shop.ShopTheme
import io.ntole.wyr.core.domain.submission.GetMySubmissions
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionRepository
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.core.domain.submission.SubmitQuestion
import io.ntole.wyr.core.domain.update.AppUpdate
import io.ntole.wyr.core.domain.vote.AttemptId
import io.ntole.wyr.core.domain.vote.CastVote
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.core.domain.vote.VoteOutcome
import io.ntole.wyr.core.domain.vote.VoteRepository
import io.ntole.wyr.core.network.InMemoryTokenStorage
import io.ntole.wyr.core.network.TokenStorage
import io.ntole.wyr.core.network.environment.WyrEnvironment
import io.ntole.wyr.di.uiModule
import io.ntole.wyr.home.HOME_COUNT_UP_MILLIS
import io.ntole.wyr.home.HOME_REVEAL_MILLIS
import io.ntole.wyr.home.PLAY_ENTRANCE_MILLIS
import io.ntole.wyr.language.EnglishStrings
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.LanguageViewModel
import io.ntole.wyr.language.SerbianCyrillicStrings
import io.ntole.wyr.language.SerbianLatinStrings
import io.ntole.wyr.language.Strings
import io.ntole.wyr.language.categoryName
import io.ntole.wyr.language.fill
import io.ntole.wyr.services.AppServices
import io.ntole.wyr.submit.sendText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    private val shops = FakeShop()
    private val categories = FakeCategories()
    private val update = FakeUpdate()
    private val uris = RecordingUris()
    private val analytics = RecordingAnalytics()
    private val storage = InMemoryTokenStorage()
    private val owner = TestOwner()
    private val clock = TestTimeSource()
    private val seen = KeptSeenDecisions()
    private var restored: Map<String, List<Any?>>? = null
    private var saved: Map<String, List<Any?>>? = null

    @BeforeTest
    fun setUp() {
        // viewModelScope runs on Dispatchers.Main, which the JVM has none of under test.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // The clock the analytics time with, which a test moves by hand.
        startKoin {
            modules(
                fakes(),
                uiModule,
                module {
                    single<TimeSource.WithComparableMarks> { clock }
                    // What runs by itself runs here and now, on the test's Main, so a test sees what it did.
                    single {
                        AppServices(get(), get(), get(), get(), get(), get(), scope = CoroutineScope(Dispatchers.Main))
                    }
                },
            )
        }
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
            assertEquals(homeTexts(CYRILLIC), scene.texts())
            assertEquals(listOf(CYRILLIC.account), scene.descriptions())
            assertEquals(0, game.questionsAsked)
        }

    /** The app opened once, and every screen it shows, each named as it is left (CLAUDE.md §8g). */
    @Test
    fun `every screen shown is reported and each left named`() =
        withApp { scene ->
            scene.tapPlay()
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

    /**
     * The player's choice is the analytics' own, which keep it for the device (CLAUDE.md §8g); the switch
     * is on the About screen, opened from the Account screen's top bar.
     */
    @Test
    fun `the Statistics switch on About turns the analytics off and on again`() =
        withApp { scene ->
            val statistics = CYRILLIC.accountScreens.statistics
            scene.tap(CYRILLIC.account)
            assertFalse(statistics in scene.texts(), "not on the Account screen")
            scene.tap(CYRILLIC.aboutScreen.title)
            assertEquals(ToggleableState.On, scene.toggleOf(statistics))

            scene.tap(statistics)
            assertFalse(analytics.enabled.value)
            assertEquals(ToggleableState.Off, scene.toggleOf(statistics))

            scene.tap(statistics)
            assertTrue(analytics.enabled.value)
            assertEquals(ToggleableState.On, scene.toggleOf(statistics))
        }

    /**
     * The shop opens from the points on Play, from the Account card's points and from the Account bar's
     * bag, each back where it was opened from (CLAUDE.md §8d, *The shop*).
     */
    @Test
    fun `the shop opens from the points and from the Account bar`() {
        game.serving = QUESTION
        withApp { scene ->
            val shop = CYRILLIC.shopScreen
            val points = CYRILLIC.points.fill(5)
            scene.tapPlay()
            scene.tap(points)
            assertTrue(shop.comingSoon in scene.everyText(), "the shop is not shown: ${scene.texts()}")
            scene.tap(CYRILLIC.back)
            assertTrue(CYRILLIC.playScreen.skip in scene.descriptions(), "back on Play: ${scene.texts()}")

            scene.tap(CYRILLIC.account)
            scene.tap(points)
            assertTrue(shop.comingSoon in scene.everyText(), "the shop from the card: ${scene.texts()}")
            scene.tap(CYRILLIC.back)
            scene.tap(shop.title)
            assertTrue(shop.comingSoon in scene.everyText(), "the shop from the bar: ${scene.texts()}")
            scene.tap(CYRILLIC.back)
            assertTrue(CYRILLIC.accountScreens.myQuestions in scene.texts(), "back on Account: ${scene.texts()}")
        }
    }

    /** A theme bought, once the dialog confirms it, is put on at once and says so on its card. */
    @Test
    fun `a theme bought is worn at once`() =
        withApp { scene ->
            val shop = CYRILLIC.shopScreen
            scene.tap(CYRILLIC.account)
            scene.tap(shop.title)
            assertEquals(1, scene.everyText().count { it == shop.active }, "the game's own is worn")

            scene.tap(shop.buy.fill(CYRILLIC.points.fill(PRICE)))
            assertTrue(shop.confirmBuy.fill(shop.neonNight) in scene.texts(), "the dialog: ${scene.texts()}")
            // The dialog's Buy and the card's under it say the same: a tap on the card's only asks again, and
            // once the dialog's has bought the theme, nothing.
            val buys = scene.nodes().filter { shop.buy.fill(CYRILLIC.points.fill(PRICE)) in it.descriptions }
            assertEquals(2, buys.size, "the card's and the dialog's")
            buys.forEach { buy ->
                assertNotNull(buy.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()
                scene.settle()
            }

            assertEquals(listOf("NEON_NIGHT"), shops.bought)
            assertEquals("player\nNEON_NIGHT", storage.read("wyr.theme.prod"))
            assertEquals(1, scene.everyText().count { it == shop.active }, "Neon night is worn: ${scene.everyText()}")
            assertTrue(shop.apply in scene.everyText(), "the game's own can be put on again")
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
            assertTrue(CYRILLIC.accountScreens.myQuestions in scene.texts(), "the Account screen is not shown")
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
     * A moderator decided a question of the player's after the launch's read: a dot on the account icon
     * of Home and of Play, named for a screen reader, until the Account screen shows the list, where its
     * row is marked; then the dot is gone (CLAUDE.md §8d, *Submitting*).
     */
    @Test
    fun `a decision not seen dots the account icon until the Account screen shows it`() {
        game.username = "bob"
        game.sent += submission("n1", SubmissionStatus.PENDING)
        val dotted = CYRILLIC.notice.accountWithNews.fill(CYRILLIC.account)
        withApp { scene ->
            assertEquals(listOf(CYRILLIC.account), scene.descriptions(), "what the launch read is not news")

            game.sent[0] = submission("n1", SubmissionStatus.APPROVED)
            // As the app coming back to the foreground, or a push while it is open, reads it again.
            KoinPlatform.getKoin().get<AppServices>().foreground()
            scene.settle()

            assertEquals(listOf(dotted), scene.descriptions(), "on Home")
            scene.tapPlay()
            assertEquals(
                listOf(CYRILLIC.home, CYRILLIC.playScreen.menu.name, dotted),
                scene.descriptions().take(PLAY_BAR.size),
                "on Play, after the menu",
            )

            scene.tap(dotted)
            // The list is read as the screen is shown, and its rows marked a frame after.
            scene.settle()
            assertTrue(CYRILLIC.notice.newMark in scene.descriptions(), "its row is marked")
            scene.tap(CYRILLIC.back)

            assertFalse(dotted in scene.descriptions(), "seen: no dot")
            assertTrue(CYRILLIC.account in scene.descriptions())
        }
    }

    /** A notification of a decision tapped: the app opens on the Account screen, back to Home below it. */
    @Test
    fun `a tapped notification opens the Account screen`() {
        game.username = "bob"
        KoinPlatform.getKoin().get<AppServices>().notificationOpened()

        withApp { scene ->
            assertTrue(CYRILLIC.accountScreens.myQuestions in scene.texts(), "the Account screen is shown")
            assertEquals(1, analytics.named(AnalyticsEvent.NOTIFICATION_OPENED).size)

            scene.tap(CYRILLIC.back)
            assertEquals(homeTexts(CYRILLIC), scene.texts())
        }
    }

    /**
     * Once any call is answered that this build is too old, the one screen shown says a new version is
     * available, whatever was shown before (CLAUDE.md §8e, *The build on every request*). The desktop
     * has no store to open, so no button.
     */
    @Test
    fun `once the server refuses the build the app shows only that a new version is available`() =
        withApp { scene ->
            scene.tapPlay()

            update.required.value = true
            scene.settle()

            assertEquals(listOf(CYRILLIC.updateScreen.newVersion), scene.texts())
            assertEquals(emptyList(), scene.descriptions())
        }

    /**
     * Delete account, on the About screen, asks first, then deletes, and goes back to the Account screen,
     * which shows the fresh guest the device plays on as (CLAUDE.md §8a, *Deleting an account*); Cancel
     * deletes nothing.
     */
    @Test
    fun `an account deleted from the About screen plays on as a guest on Account`() {
        game.username = "bob"
        withApp { scene ->
            val strings = CYRILLIC.accountScreens
            scene.tap(CYRILLIC.account)
            assertFalse(strings.deleteAccount.button in scene.texts(), "not on the Account screen")
            scene.tap(CYRILLIC.aboutScreen.title)

            scene.tap(strings.deleteAccount.button)
            assertTrue(strings.deleteAccount.warning in scene.texts(), "${scene.texts()}")
            scene.tap(CYRILLIC.cancel)
            assertEquals(emptyList(), game.deleted)

            scene.tap(strings.deleteAccount.button)
            scene.tap(strings.deleteAccount.confirm)

            assertEquals(listOf<String?>("bob"), game.deleted)
            assertTrue(strings.myQuestions in scene.texts(), "back on Account: ${scene.texts()}")
            assertTrue(strings.guest in scene.texts(), "${scene.texts()}")
            assertTrue(strings.openAuth in scene.texts(), "a guest's one button")
            assertEquals(1, analytics.named(AnalyticsEvent.ACCOUNT_DELETED).size)
        }
    }

    /** The Account screen's info icon opens the About screen, with the app's version, and back returns. */
    @Test
    fun `the About screen opens from the Account screen's top bar and shows the version`() =
        withApp { scene ->
            scene.tap(CYRILLIC.account)
            scene.tap(CYRILLIC.aboutScreen.title)

            assertTrue(CYRILLIC.aboutScreen.version.fill("1.0.0 (10000)") in scene.texts(), "${scene.texts()}")
            assertTrue("player" in scene.texts(), "the stored session's account id: ${scene.texts()}")
            val shown = analytics.named(RecordingAnalytics.SCREEN).map { it.properties[RecordingAnalytics.SCREEN_NAME] }
            assertEquals("about", shown.last())

            scene.tap(CYRILLIC.back)
            assertTrue(CYRILLIC.accountScreens.myQuestions in scene.texts(), "back on Account: ${scene.texts()}")
        }

    /**
     * The menu on Play's top bar hides the question for good (CLAUDE.md §8d, *Reports*): a report, for
     * the reason tapped, goes to the server, and the next question shows.
     */
    @Test
    fun `a report from Play's menu sends it and moves on`() {
        game.serving = QUESTION
        withApp { scene ->
            scene.tapPlay()
            val menu = CYRILLIC.playScreen.menu

            scene.tap(menu.name)
            scene.tap(menu.report)
            scene.tap(menu.spam)

            assertEquals(listOf("report ${QUESTION.id} SPAM"), game.reported)
            assertEquals(2, game.questionsAsked)
            assertEquals(1, analytics.named(AnalyticsEvent.QUESTION_REPORTED).size)
        }
    }

    /** Home's picks move with every player's taps, so Home reads them each time it is shown (§8d, *Home picks*). */
    @Test
    fun `Home reads the picks each time it is shown`() =
        withApp { scene ->
            assertEquals(1, game.picksRead)

            scene.tap(CYRILLIC.account)
            scene.tap(CYRILLIC.back)

            assertEquals(homeTexts(CYRILLIC), scene.texts())
            assertEquals(2, game.picksRead)
        }

    /**
     * A tap on either Play button reveals both shares, the tap counted on the device, while the server
     * hears of it in the background and the question loads; Play opens once the reveal is over, whether
     * or not the tap was counted by then.
     */
    @Test
    fun `a tap on Home's Play reveals the shares then opens Play and is counted by its side`() {
        val counted = CompletableDeferred<Unit>()
        game.pickWaitsFor = counted
        withApp { scene ->
            val (_, cardB) = scene.nodes().filter { CYRILLIC.play in it.texts }
            assertTrue(
                cardB.config
                    .getOrNull(SemanticsActions.OnClick)
                    ?.action
                    ?.invoke() == true,
            )
            scene.settle()

            // Three taps on card A's colour, and one on card B's before this one.
            assertEquals(
                listOf(
                    CYRILLIC.gameName,
                    CYRILLIC.play,
                    CYRILLIC.playScreen.percent(60),
                    CYRILLIC.play,
                    CYRILLIC.playScreen.percent(40),
                ),
                scene.texts(),
            )
            assertEquals(1, game.questionsAsked, "the question loads during the reveal")

            scene.passTime(HOME_REVEAL_MILLIS + PLAY_ENTRANCE_MILLIS.toLong())
            assertEquals(PLAY_BAR, scene.descriptions().take(PLAY_BAR.size))
            assertEquals(1, game.questionsAsked)
            assertEquals(emptyList(), game.picked, "not counted yet")
            counted.complete(Unit)
            scene.settle()
            assertEquals(listOf(Side.B), game.picked)
        }
    }

    @Test
    fun `Play opens under a bar with home and the account icon`() =
        withApp { scene ->
            scene.tapPlay()

            assertEquals(PLAY_BAR, scene.descriptions().take(PLAY_BAR.size))
            assertEquals(1, game.questionsAsked)
        }

    /**
     * Skip is in the row between the cards while a question is asked, after the thumbs and Share, and not on the
     * top bar, which holds home, the categories played and the account icon; it goes past the question
     * to the next. The points, a coin and the number, a screen reader hears in words, last, a little
     * lower in the row than the thumbs' touch targets.
     */
    @Test
    fun `Skip between the cards skips the question asked`() {
        game.serving = QUESTION
        withApp { scene ->
            scene.tapPlay()
            assertEquals(
                PLAY_BAR +
                    listOf(CYRILLIC.playScreen.like, CYRILLIC.playScreen.dislike, CYRILLIC.playScreen.share.share) +
                    CYRILLIC.playScreen.skip +
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
            scene.tapPlay()
            scene.tap(CYRILLIC.account)
            assertTrue(CYRILLIC.accountScreens.myQuestions in scene.texts(), "the Account screen is not shown")

            scene.tap(CYRILLIC.back)

            assertEquals(PLAY_BAR, scene.descriptions().take(PLAY_BAR.size))
            assertEquals(1, game.questionsAsked)
        }

    /** The points move on the Account and Submit screens, so Play reads them each time it is shown. */
    @Test
    fun `Play reads the points each time it is shown`() =
        withApp { scene ->
            scene.tapPlay()
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
            scene.tapPlay()
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
            scene.tapPlay()
            scene.tap(CYRILLIC.home)
            assertEquals(homeTexts(CYRILLIC), scene.texts())

            scene.tapPlay()

            assertEquals(1, game.questionsAsked)
        }

    @Test
    fun `Account opens from Home and its back arrow returns to Home`() =
        withApp { scene ->
            scene.tap(CYRILLIC.account)
            assertEquals(1, game.statsRead)

            scene.tap(CYRILLIC.back)

            assertEquals(homeTexts(CYRILLIC), scene.texts())
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

            assertTrue(CYRILLIC.accountScreens.myQuestions in scene.texts(), "the Account screen is not shown")
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
            assertTrue(CYRILLIC.accountScreens.myQuestions in shown, "the Account screen is not shown: $shown")
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
            assertTrue(CYRILLIC.accountScreens.myQuestions in shown, "the Account screen is not shown: $shown")
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

            assertEquals(homeTexts(CYRILLIC), scene.texts())
        }

    @Test
    fun `the categories open from Play and Play plays what is picked there`() =
        withApp { scene ->
            scene.tapPlay()
            scene.tap(ALL_PLAYED)
            assertEquals(listOf(CYRILLIC.back), scene.descriptions().take(1))
            assertEquals(1, categories.reads, "read as the screen opens")

            scene.tap("Храна")
            scene.tap("Етика")
            scene.tapPlay()

            assertEquals(PLAY_BAR, scene.descriptions().take(PLAY_BAR.size))
            assertEquals(listOf(setOf("FOOD", "ETHICS")), game.categoryChanges)
            assertEquals(2, game.questionsAsked, "a question from them")
            assertTrue("Храна, Етика" in scene.texts(), "${scene.texts()}")
        }

    @Test
    fun `back from the categories plays nothing picked there`() =
        withApp { scene ->
            scene.tapPlay()
            scene.tap(ALL_PLAYED)
            scene.tap("Храна")

            scene.tap(CYRILLIC.back)

            assertEquals(PLAY_BAR, scene.descriptions().take(PLAY_BAR.size))
            assertEquals(emptyList(), game.categoryChanges)
            assertEquals(1, game.questionsAsked)
            scene.tap(ALL_PLAYED)
            assertEquals(ToggleableState.Off, scene.toggleOf("Храна"), "a visit starts afresh")
            assertEquals(ToggleableState.On, scene.toggleOf(CYRILLIC.allCategories))
        }

    /** The language menu is off the Account screen for now (CLAUDE.md §8f): no language is offered there. */
    @Test
    fun `the Account screen offers no language`() =
        withApp { scene ->
            scene.tap(CYRILLIC.account)

            val shown = scene.everyText() + scene.descriptions()
            Language.entries.forEach { language -> assertFalse(language.ownName in shown, "$language in $shown") }
            assertFalse(shown.any { it.startsWith(CYRILLIC.language) }, "$shown")
        }

    /**
     * A question's row in My questions opens its details, each option whole with its share, and back
     * returns to the Account screen (CLAUDE.md §8d, *Question details*).
     */
    @Test
    fun `a question of My questions opens whole and back returns to Account`() =
        withApp { scene ->
            game.username = "bob"
            game.sent +=
                Submission(
                    id = "mine",
                    optionA = "Fly",
                    optionB = "Swim",
                    categories = setOf(FOOD.id),
                    status = SubmissionStatus.APPROVED,
                    rejectionReason = null,
                    submittedAt = Instant.parse("2026-09-25T12:00:00Z"),
                    answerCount = 4,
                    tally = Tally(votesA = 3, votesB = 1),
                )
            scene.tap(CYRILLIC.account)

            scene.tap("Fly ${CYRILLIC.accountScreens.or} Swim")
            scene.passTime(HOME_COUNT_UP_MILLIS.toLong())

            val shown = scene.texts()
            listOf("Fly", "Swim", "75%", "25%", CYRILLIC.accountScreens.approved).forEach { text ->
                assertTrue(text in shown, "\"$text\" is not in $shown")
            }
            assertTrue(categoryName(FOOD, Language.SERBIAN_CYRILLIC) in shown, "its category: $shown")
            val screens =
                analytics
                    .named(
                        RecordingAnalytics.SCREEN,
                    ).map { it.properties[RecordingAnalytics.SCREEN_NAME] }
            assertEquals("question", screens.last())

            scene.tap(CYRILLIC.back)
            assertTrue(CYRILLIC.accountScreens.myQuestions in scene.texts(), "back on Account: ${scene.texts()}")
        }

    @Test
    fun `the app opens in the language kept on the device`() {
        runBlocking { storage.write(LanguageViewModel.KEY, Language.ENGLISH.tag) }

        withApp { scene -> assertEquals(homeTexts(ENGLISH), scene.texts()) }
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
                    // A link opens through the test's own handler, never the machine's browser.
                    LocalUriHandler provides uris,
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
            single<ReportRepository> { game }
            single<HomePickRepository> { game }
            single<PlayerRepository> { game }
            single<AccountRepository> { game }
            single<SubmissionRepository> { game }
            single<CategoryRepository> { categories }
            single<ShopRepository> { shops }
            factory { GetShop(shop = get(), session = get()) }
            factory { BuyTheme(shop = get(), session = get()) }
            single<Analytics> { analytics }
            single<AppUpdate> { update }
            single { AppVersion(name = "1.0.0", number = 10000) }
            factory { GetNextQuestion(questions = get(), session = get()) }
            factory { SkipQuestion(questions = get(), session = get()) }
            factory { CastVote(votes = get(), session = get()) }
            factory { SetReaction(reactions = get(), session = get()) }
            factory { ReportQuestion(reports = get(), session = get()) }
            factory { HideQuestion(reports = get(), session = get()) }
            factory { HideAuthor(reports = get(), questions = get(), session = get()) }
            factory { GetHomePicks(picks = get()) }
            factory { PickOnHome(picks = get(), session = get()) }
            factory { GetPlayerStats(players = get(), session = get()) }
            factory { RegisterAccount(accounts = get(), session = get(), analytics = get()) }
            factory { LogIn(accounts = get(), questions = get(), session = get(), analytics = get()) }
            factory { LogOut(accounts = get(), questions = get(), analytics = get()) }
            factory { DeleteAccount(accounts = get(), questions = get(), analytics = get()) }
            factory { SubmitQuestion(submissions = get(), session = get()) }
            factory { GetMySubmissions(submissions = get(), session = get()) }
            factory { GetCategories(categories = get()) }
            single<CurrentSession> { game }
            single { LinkPlayGames(PlayGames.None, NoPlayGamesLink, get(), get(), get()) }
            single<DevicePush> { DevicePush.None }
            // The questions the notice reads, the game's own list, read without counting a read of it.
            single { DecisionNotices(ListedWithoutCounting(game), get(), seen) }
            factory { KeepPushTokenRegistered(get(), NoPushTokens, get()) }
        }

    /**
     * Taps the first node showing Play: on Home, the button in card A's colour, one of two that start
     * the game alike; on the Categories screen, its one Play.
     */
    private fun ImageComposeScene.tapPlay() {
        val node = nodes().firstOrNull { CYRILLIC.play in it.texts || ENGLISH.play in it.texts }
        val tap = node?.config?.getOrNull(SemanticsActions.OnClick)?.action
        assertTrue(tap != null, "nothing to tap shows Play: ${texts()}")
        tap()
        settle()
        // Home's reveal, then its fade into Play; nothing on the Categories screen waits for it.
        passTime(HOME_REVEAL_MILLIS + PLAY_ENTRANCE_MILLIS.toLong())
    }

    /** What Home shows in [strings]' words before a tap: the name and two Play buttons, no share yet. */
    private fun homeTexts(strings: Strings): List<String> = listOf(strings.gameName, strings.play, strings.play)

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

    /** Play Games is never there in these builds, so nothing asks this. */
    private object NoPlayGamesLink : PlayGamesRepository {
        override fun isSettled(): Boolean = error("no Play Games here")

        override suspend fun signIn(serverAuthCode: String): String? = error("no Play Games here")
    }

    /** The game's questions for the notice, read without counting a read of them, which the screens' are. */
    private class ListedWithoutCounting(
        private val game: FakeGame,
    ) : SubmissionRepository by game {
        override suspend fun mine(): List<Submission> = game.sent.toList()
    }

    private class KeptSeenDecisions : SeenDecisionsStore {
        private var kept: SeenDecisions? = null

        override fun read(): SeenDecisions? = kept

        override suspend fun write(seen: SeenDecisions) {
            kept = seen
        }
    }

    /** Pushes are never there in these builds, so nothing registers a token. */
    private object NoPushTokens : PushTokenRepository {
        override suspend fun register(
            token: String,
            platform: PushPlatform,
        ): Unit = error("no pushes here")
    }

    /** The shop: Neon night on sale, the player registered with enough points; a purchase owns it. */
    private class FakeShop : ShopRepository {
        val bought = mutableListOf<String>()
        private var shop = Shop(listOf(ShopTheme("NEON_NIGHT", PRICE, owned = false)), points = 500, registered = true)

        override suspend fun shop(): Shop = shop

        override suspend fun buy(themeId: String): Shop {
            bought += themeId
            shop =
                shop.copy(
                    themes = shop.themes.map { it.copy(owned = it.owned || it.id == themeId) },
                    points =
                        500 - PRICE,
                )
            return shop
        }
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
        ReportRepository,
        HomePickRepository,
        PlayerRepository,
        AccountRepository,
        SubmissionRepository,
        CurrentSession {
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

        override fun current(): String = "player"

        override val sessions: Flow<String> = flowOf("player")

        override suspend fun cast(
            questionId: String,
            side: Side,
            attempt: AttemptId,
            answerMillis: Long?,
        ): VoteOutcome = error("nothing votes here")

        override suspend fun setReaction(
            questionId: String,
            reaction: Reaction,
        ): QuestionReactions = error("nothing reacts here")

        /** Every read of Home's picks. */
        var picksRead = 0

        /** Every tap on Home's Play buttons, by side, in order. */
        val picked = mutableListOf<Side>()

        /** When set, a tap on Home's Play waits for it before it is counted. */
        var pickWaitsFor: CompletableDeferred<Unit>? = null

        override suspend fun counts(): Tally {
            picksRead++
            return HOME_PICKS
        }

        override suspend fun pick(side: Side): Tally {
            pickWaitsFor?.await()
            picked += side
            return HOME_PICKS
        }

        /** Every report and hide, in order. */
        val reported = mutableListOf<String>()

        override suspend fun report(
            questionId: String,
            reason: ReportReason,
        ) {
            reported += "report $questionId $reason"
        }

        override suspend fun hideQuestion(questionId: String) {
            reported += "hide question $questionId"
        }

        override suspend fun hideAuthor(questionId: String) {
            reported += "hide author $questionId"
        }

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

    private fun submission(
        id: String,
        status: SubmissionStatus,
    ) = Submission(
        id = id,
        optionA = "$id-a",
        optionB = "$id-b",
        categories = setOf(FOOD.id),
        status = status,
        rejectionReason = null,
        submittedAt = Instant.fromEpochMilliseconds(1_790_000_000_000L),
    )

    private companion object {
        val CYRILLIC = SerbianCyrillicStrings
        val ENGLISH = EnglishStrings
        val LATIN = SerbianLatinStrings

        val FOOD = Category(id = "FOOD", nameSr = "Храна", nameEn = "Food")

        val LISTED = listOf(FOOD, Category(id = "ETHICS", nameSr = "Етика", nameEn = "Ethics"))

        val QUESTION = Question(id = "q1", optionA = "Fly", optionB = "Swim", categories = setOf(FOOD.id))

        /** Every player's taps on Home's two Play buttons: three on card A's colour to every one on card B's. */
        val HOME_PICKS = Tally(votesA = 3, votesB = 1)

        /** What a screen reader hears first on Play: its top bar's icons, the question's menu among them. */
        val PLAY_BAR = listOf(CYRILLIC.home, CYRILLIC.playScreen.menu.name, CYRILLIC.account)

        /** The Play screen's categories, All while none is played: a tap on them opens the Categories screen. */
        val ALL_PLAYED = CYRILLIC.allCategories

        /** What a theme costs in the fake shop. */
        const val PRICE = 220
    }
}
