package io.ntole.wyr

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.ntole.wyr.core.domain.account.AccountRepository
import io.ntole.wyr.core.domain.account.LogIn
import io.ntole.wyr.core.domain.account.LogOut
import io.ntole.wyr.core.domain.account.RegisterAccount
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.like.LikeRepository
import io.ntole.wyr.core.domain.like.QuestionLikes
import io.ntole.wyr.core.domain.like.SetLike
import io.ntole.wyr.core.domain.player.GetPlayerStats
import io.ntole.wyr.core.domain.player.PlayerRepository
import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.question.GetNextQuestion
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.question.QuestionRepository
import io.ntole.wyr.core.domain.question.SkipQuestion
import io.ntole.wyr.core.domain.session.SessionRepository
import io.ntole.wyr.core.domain.submission.GetMySubmissions
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionRepository
import io.ntole.wyr.core.domain.submission.SubmitQuestion
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import kotlin.test.assertTrue

/**
 * The whole app, [App] as every platform shows it, drawn off screen over fakes of the game and driven
 * by tapping its buttons (CLAUDE.md §8d, *Navigation*; §8f). The fakes count what the screens ask for,
 * which says whether a screen's ViewModel lived on while another was shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppNavigationTest {
    private val game = FakeGame()
    private val storage = InMemoryTokenStorage()
    private val owner = TestOwner()

    @BeforeTest
    fun setUp() {
        // viewModelScope runs on Dispatchers.Main, which the JVM has none of under test.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        startKoin { modules(fakes(), uiModule) }
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

    @Test
    fun `Play opens under a bar with home and the account icon`() =
        withApp { scene ->
            scene.tap(CYRILLIC.play)

            assertEquals(listOf(CYRILLIC.home, CYRILLIC.account), scene.descriptions().take(2))
            assertEquals(1, game.questionsAsked)
        }

    /** Skip is on Play's top bar while a question is asked, and goes past it to the next. */
    @Test
    fun `Skip on the Play screen's top bar skips the question asked`() {
        game.serving = QUESTION
        withApp { scene ->
            scene.tap(CYRILLIC.play)
            assertEquals(
                listOf(CYRILLIC.home, CYRILLIC.playScreen.skip, CYRILLIC.account),
                scene.descriptions().take(3),
            )

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
            assertTrue(CYRILLIC.submitQuestion in scene.texts(), "the Account screen is not shown")

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

    @Test
    fun `Submit opens from Account and its back arrow returns to Account`() =
        withApp { scene ->
            scene.tap(CYRILLIC.account)
            scene.tap(CYRILLIC.submitQuestion)
            assertEquals(1, game.submissionsRead)
            assertEquals(listOf(CYRILLIC.back), scene.descriptions().take(1))

            scene.tap(CYRILLIC.back)

            assertTrue(CYRILLIC.submitQuestion in scene.texts(), "the Account screen is not shown")
        }

    /** The switch changes the screen it is on at once, and every screen after it, and is kept. */
    @Test
    fun `the language switch changes every screen at once and is kept`() =
        withApp { scene ->
            scene.tap(CYRILLIC.account)

            scene.tap(Language.ENGLISH.ownName)

            assertTrue(ENGLISH.submitQuestion in scene.texts(), "${scene.texts()}")
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

    /** Draws [App] as a 375 by 599 phone, a lifecycle and a ViewModel owner of the test's around it. */
    private fun withApp(test: (ImageComposeScene) -> Unit) {
        val scene =
            ImageComposeScene(width = 375, height = 599, density = Density(1f)) {
                CompositionLocalProvider(
                    LocalLifecycleOwner provides owner,
                    LocalViewModelStoreOwner provides owner,
                ) { App() }
            }
        try {
            scene.settle()
            test(scene)
        } finally {
            scene.close()
        }
    }

    private fun fakes() =
        module {
            single<TokenStorage> { storage }
            single { WyrEnvironment.PROD }
            single<QuestionRepository> { game }
            single<SessionRepository> { game }
            single<VoteRepository> { game }
            single<LikeRepository> { game }
            single<PlayerRepository> { game }
            single<AccountRepository> { game }
            single<SubmissionRepository> { game }
            factory { GetNextQuestion(questions = get(), session = get()) }
            factory { SkipQuestion(questions = get(), session = get()) }
            factory { CastVote(votes = get(), session = get()) }
            factory { SetLike(likes = get(), session = get()) }
            factory { GetPlayerStats(players = get(), session = get()) }
            factory { RegisterAccount(accounts = get(), session = get()) }
            factory { LogIn(accounts = get(), questions = get()) }
            factory { LogOut(accounts = get(), questions = get()) }
            factory { SubmitQuestion(submissions = get(), session = get()) }
            factory { GetMySubmissions(submissions = get(), session = get()) }
        }

    /** A resumed lifecycle and a ViewModel store, as an activity or a window gives the app. */
    private class TestOwner :
        LifecycleOwner,
        ViewModelStoreOwner {
        override val lifecycle: Lifecycle =
            LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }

        override val viewModelStore: ViewModelStore = ViewModelStore()
    }

    /**
     * The game, counting what the screens ask of it. Out of questions unless it is [serving] one, so
     * the Play screen shows a failure; nothing here votes, likes or registers.
     */
    private class FakeGame :
        QuestionRepository,
        SessionRepository,
        VoteRepository,
        LikeRepository,
        PlayerRepository,
        AccountRepository,
        SubmissionRepository {
        var questionsAsked = 0
        var statsRead = 0
        var submissionsRead = 0

        /** The question every fetch serves, or none: out of questions, which needs none made. */
        var serving: Question? = null

        val skipped = mutableListOf<String>()

        override val categories: StateFlow<Set<Category>> = MutableStateFlow(emptySet())

        override suspend fun next(): Question {
            questionsAsked++
            return serving ?: throw WyrException(DomainError.OUT_OF_QUESTIONS)
        }

        override suspend fun prefetch() = Unit

        override suspend fun setCategories(categories: Set<Category>) = Unit

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

        override suspend fun setLiked(
            questionId: String,
            liked: Boolean,
        ): QuestionLikes = error("nothing likes here")

        override suspend fun stats(): PlayerStats {
            statsRead++
            return PlayerStats(0, 0, 0, 1, 10, 0)
        }

        override suspend fun register(
            username: String,
            password: String,
        ): String = error("nothing registers here")

        override suspend fun logIn(
            username: String,
            password: String,
        ) = error("nothing logs in here")

        override suspend fun logOut() = error("nothing logs out here")

        override suspend fun submit(
            optionA: String,
            optionB: String,
            categories: Set<Category>,
        ): Submission = error("nothing submits here")

        override suspend fun mine(): List<Submission> {
            submissionsRead++
            return emptyList()
        }
    }

    private companion object {
        val CYRILLIC = SerbianCyrillicStrings
        val ENGLISH = EnglishStrings

        val QUESTION = Question(id = "q1", optionA = "Fly", optionB = "Swim", categories = setOf(Category.FOOD))
    }
}
