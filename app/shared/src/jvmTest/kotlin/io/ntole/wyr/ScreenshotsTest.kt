package io.ntole.wyr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import io.ntole.wyr.account.AccountActions
import io.ntole.wyr.account.AccountScreen
import io.ntole.wyr.account.AccountState
import io.ntole.wyr.account.AuthMode
import io.ntole.wyr.categories.CategoriesActions
import io.ntole.wyr.categories.CategoriesScreen
import io.ntole.wyr.categories.CategoriesState
import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.reaction.Reaction
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.core.domain.vote.VoteOutcome
import io.ntole.wyr.core.network.environment.WyrEnvironment
import io.ntole.wyr.home.HomeScreen
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.WyrStrings
import io.ntole.wyr.navigation.AccountTopBar
import io.ntole.wyr.navigation.BackTopBar
import io.ntole.wyr.navigation.PlayTopBar
import io.ntole.wyr.play.CategoriesPlayed
import io.ntole.wyr.play.PlayScreen
import io.ntole.wyr.play.PlayUiState
import io.ntole.wyr.theme.GameTheme
import io.ntole.wyr.theme.GameThemes
import io.ntole.wyr.theme.LocalPageDrawn
import io.ntole.wyr.theme.WholePageArt
import io.ntole.wyr.theme.WyrTheme
import io.ntole.wyr.theme.WyrThemeAccessors
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The game's main screens as a phone shows them, each under its top bar on the page the app draws under
 * the whole window, the theme's art on it (CLAUDE.md §5b, *Backgrounds*), once every animation has
 * ended, in the game's own theme light and dark and in one of the shop's: Home, Play asked and revealed
 * (and revealed on a phone on its side), Account and Categories. Each is drawn in every run; with
 * `WYR_SCREENSHOTS_DIR` set, each is also written there as a PNG, for a person to look at:
 * `WYR_SCREENSHOTS_DIR=/tmp/wyr-shots ./gradlew :app:shared:jvmTest --tests io.ntole.wyr.ScreenshotsTest --rerun`.
 */
class ScreenshotsTest {
    @Test
    fun `the main screens draw on the page in each theme`() {
        val directory =
            System
                .getenv(DIRECTORY)
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
                ?.also { it.mkdirs() }
        SCREENS.forEach { shot ->
            LOOKS.forEach { (look, theme, dark) ->
                val scene =
                    ImageComposeScene(width = shot.width, height = shot.height, density = Density(DENSITY)) {
                        WyrTheme(theme = theme, darkTheme = dark) {
                            WyrStrings(
                                Language.DEFAULT,
                            ) { OnWholePage { Column(Modifier.fillMaxSize()) { shot.content(this) } } }
                        }
                    }
                try {
                    // A frame at a time, as a screen draws, past every animation, the count up's 3 seconds the longest.
                    (0..SETTLED / FRAME).forEach { frame -> scene.renderAt(frame * FRAME) }
                    val image = scene.render(SETTLED)
                    assertEquals(shot.width, image.width, shot.name)
                    directory?.let { File(it, "${shot.name}-$look.png") }?.writeBytes(
                        checkNotNull(image.encodeToData()) { "${shot.name} encodes to nothing" }.bytes,
                    )
                } finally {
                    scene.close()
                }
            }
        }
    }

    /** [content] over the page and its art, as `App`'s `WholePage` draws them, so the screen draws neither. */
    @Composable
    private fun OnWholePage(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            WholePageArt(Modifier.matchParentSize())
            CompositionLocalProvider(LocalPageDrawn provides true) {
                Surface(
                    color = Color.Transparent,
                    contentColor = WyrThemeAccessors.colors.primaryText,
                    modifier = Modifier.fillMaxSize(),
                    content = content,
                )
            }
        }
    }

    /** A screen to draw, [width] by [height] at [DENSITY] pixels a dp. */
    private class Shot(
        val name: String,
        val width: Int = PHONE_WIDTH,
        val height: Int = PHONE_HEIGHT,
        val content: @Composable ColumnScope.() -> Unit,
    )

    /** A theme to draw each screen in, named for the file: the game's own in each mode, or one of the shop's. */
    private data class Look(
        val name: String,
        val theme: GameTheme,
        val dark: Boolean,
    )

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
        const val DIRECTORY = "WYR_SCREENSHOTS_DIR"

        /** An iPhone SE's 375 by 667, at twice a pixel a dp, as its screen draws. */
        const val DENSITY = 2f
        const val PHONE_WIDTH = 750
        const val PHONE_HEIGHT = 1334
        const val SETTLED = 4_000_000_000L
        const val FRAME = 1_000_000_000L / 60

        val LOOKS =
            listOf(
                Look("light", GameThemes.Default, dark = false),
                Look("dark", GameThemes.Default, dark = true),
                Look("ocean", GameThemes.Ocean, dark = false),
            )

        val QUESTION =
            Question(
                id = "q1",
                optionA = "Да сваког дана доручкујеш пицу",
                optionB = "Да сваког дана вечераш бурек",
                categories = setOf("FOOD"),
                likeCount = 12,
                dislikeCount = 3,
                myReaction = Reaction.LIKE,
            )
        val OUTCOME =
            VoteOutcome(yourSide = Side.A, tally = Tally(votesA = 62, votesB = 38), pointsAwarded = 1, totalPoints = 43)
        val CATEGORIES =
            listOf(
                Category(id = "FOOD", nameSr = "Храна", nameEn = "Food"),
                Category(id = "LIFESTYLE", nameSr = "Начин живота", nameEn = "Lifestyle"),
                Category(id = "ETHICS", nameSr = "Етика", nameEn = "Ethics"),
                Category(id = "SUPERPOWERS", nameSr = "Супермоћи", nameEn = "Superpowers"),
                Category(id = "ABSURD", nameSr = "Апсурдно", nameEn = "Absurd"),
                Category(id = "TRAVEL", nameSr = "Путовања", nameEn = "Travel"),
            )

        @Composable
        fun ColumnScope.Play(state: PlayUiState) {
            PlayTopBar(onHome = {}, onAccount = {}) {
                CategoriesPlayed(text = "Храна", enabled = true, onClick = {})
            }
            Box(Modifier.weight(1f)) {
                PlayScreen(
                    state = state,
                    points = 43,
                    onChoose = {},
                    onSkip = {},
                    onNext = {},
                    onReact = {},
                    onRetry = {},
                )
            }
        }

        val SCREENS =
            listOf(
                Shot("home") {
                    HomeScreen(picks = Tally(votesA = 30, votesB = 18), onPick = {}, onPlay = {}, onAccount = {})
                },
                Shot("play-asked") { Play(PlayUiState.Asking(QUESTION)) },
                Shot("play-revealed") { Play(PlayUiState.Revealed(QUESTION, OUTCOME)) },
                Shot("play-revealed-wide", width = PHONE_HEIGHT, height = PHONE_WIDTH) {
                    Play(PlayUiState.Revealed(QUESTION, OUTCOME))
                },
                Shot("account") {
                    AccountTopBar(onBack = {}, onAbout = {})
                    Box(Modifier.weight(1f)) {
                        AccountScreen(
                            state =
                                AccountState(
                                    stats = PlayerStats(totalPoints = 43, questionsAnswered = 40, username = "nikola"),
                                    submissions =
                                        listOf(
                                            Submission(
                                                id = "s1",
                                                optionA = "Да летиш",
                                                optionB = "Да будеш невидљив",
                                                categories = setOf("SUPERPOWERS"),
                                                status = SubmissionStatus.APPROVED,
                                                rejectionReason = null,
                                                submittedAt = Instant.parse("2026-09-25T12:00:00Z"),
                                                likeCount = 5,
                                                dislikeCount = 1,
                                                answerCount = 31,
                                            ),
                                        ),
                                ),
                            actions = NoAccountActions,
                            environment = WyrEnvironment.PROD,
                            onOpenAuth = {},
                            onNewQuestion = {},
                        )
                    }
                },
                Shot("categories") {
                    BackTopBar(onBack = {})
                    Box(Modifier.weight(1f)) {
                        CategoriesScreen(
                            state =
                                CategoriesState(
                                    ticked = setOf("FOOD"),
                                    categories = CATEGORIES,
                                    found = CATEGORIES,
                                ),
                            actions = NoCategoriesActions,
                        )
                    }
                },
            )
    }
}
