package io.ntole.wyr.analytics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import io.ntole.wyr.RecordingClipboard
import io.ntole.wyr.RecordingUris
import io.ntole.wyr.about.AboutScreen
import io.ntole.wyr.about.AppVersion
import io.ntole.wyr.account.AccountAction
import io.ntole.wyr.account.AccountActions
import io.ntole.wyr.account.AccountFailure
import io.ntole.wyr.account.AccountScreen
import io.ntole.wyr.account.AccountState
import io.ntole.wyr.account.AuthMode
import io.ntole.wyr.account.AuthScreen
import io.ntole.wyr.account.DeleteAccount
import io.ntole.wyr.account.QuestionDetailsScreen
import io.ntole.wyr.categories.CategoriesActions
import io.ntole.wyr.categories.CategoriesScreen
import io.ntole.wyr.categories.CategoriesState
import io.ntole.wyr.core.domain.analytics.AnalyticsEvent
import io.ntole.wyr.core.domain.analytics.AnalyticsProperty
import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.player.PlayerStats
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.report.ReportReason
import io.ntole.wyr.core.domain.shop.Shop
import io.ntole.wyr.core.domain.shop.ShopTheme
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.core.domain.vote.VoteOutcome
import io.ntole.wyr.core.network.environment.WyrEnvironment
import io.ntole.wyr.descriptions
import io.ntole.wyr.home.HomeScreen
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.LanguageMenu
import io.ntole.wyr.language.SerbianCyrillicStrings
import io.ntole.wyr.language.WyrStrings
import io.ntole.wyr.navigation.AccountTopBar
import io.ntole.wyr.navigation.BackTopBar
import io.ntole.wyr.navigation.PlayTopBar
import io.ntole.wyr.nodes
import io.ntole.wyr.play.CategoriesPlayed
import io.ntole.wyr.play.PlayScreen
import io.ntole.wyr.play.PlayUiState
import io.ntole.wyr.play.QuestionMenu
import io.ntole.wyr.settle
import io.ntole.wyr.shop.ShopActions
import io.ntole.wyr.shop.ShopFailure
import io.ntole.wyr.shop.ShopScreen
import io.ntole.wyr.shop.ShopState
import io.ntole.wyr.submit.SubmitActions
import io.ntole.wyr.submit.SubmitFailure
import io.ntole.wyr.submit.SubmitScreen
import io.ntole.wyr.submit.SubmitState
import io.ntole.wyr.tap
import io.ntole.wyr.texts
import io.ntole.wyr.theme.GameThemes
import io.ntole.wyr.theme.WyrTheme
import io.ntole.wyr.update.UpdateButton
import io.ntole.wyr.update.UpdateScreen
import io.ntole.wyr.update.UpdateWay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Every tap on every screen is reported, by a stable name (CLAUDE.md §8g): each screen is drawn off
 * screen in the states that show all it can be tapped on, and everything a screen reader could tap,
 * but a text field, is tapped, and must report exactly one [AnalyticsEvent.TAP], whose element is
 * named `screen.what`. So a button added later without [tapped] fails here.
 *
 * The names are listed, screen by screen, since a name once sent never changes: a dashboard built on
 * it would lose it.
 */
class TapsTest {
    private val analytics = RecordingAnalytics()

    /** Every URL a tap asked to open: nothing here reaches a browser. */
    private val uris = RecordingUris()

    @Test
    fun `every tap on Home and the top bars is reported`() {
        // A tap on either Play button holds the other still through the reveal, so each is a Home of its own,
        // the other left untapped: from the top, the account icon, then card A's colour and card B's, and
        // under them the categories played, which open the Categories screen.
        listOf(2, 1).forEach { other ->
            assertEquals(
                setOf("home.play", "top_bar.account", "home.categories"),
                elementsTapped(skipping = other) {
                    HomeScreen(
                        picks = Tally(votesA = 3, votesB = 1),
                        onPick = {},
                        onPlay = {},
                        onAccount = {},
                        categories = "Све",
                        onCategories = {},
                    )
                },
            )
        }
        // Home's two Play buttons are one element, told apart by their side (CLAUDE.md §8d, *Home picks*).
        val sides =
            analytics
                .named(AnalyticsEvent.TAP)
                .filter { it.properties[AnalyticsProperty.ELEMENT] == "home.play" }
                .map { it.properties[AnalyticsProperty.SIDE] }
        assertEquals(listOf("A", "B"), sides)
        assertEquals(
            setOf("top_bar.home", "top_bar.categories", "top_bar.account"),
            elementsTapped {
                PlayTopBar(onHome = {}, onAccount = {}, news = true) {
                    CategoriesPlayed(text = "Све", enabled = true, onClick = {})
                }
            },
        )
        assertEquals(setOf("top_bar.back"), elementsTapped { BackTopBar(onBack = {}) })
        assertEquals(
            setOf("top_bar.back", "top_bar.shop", "top_bar.about"),
            elementsTapped { AccountTopBar(onBack = {}, onAbout = {}, onShop = {}) },
        )
    }

    /** Each of the site's pages, and a licence's text: opened by the test's own handler, never a browser. */
    @Test
    fun `every tap on the About screen is reported`() {
        assertEquals(
            setOf(
                "about.privacy",
                "about.terms",
                "about.delete_account",
                "about.contact",
                "about.copy_account_id",
                "about.licence",
                // The Statistics switch, and its info icon and the dialog's OK, named as on the Account screen
                // they left (CLAUDE.md §8g): a name once sent never changes.
                "account.statistics",
                "account.statistics_info",
                "account.statistics_info_ok",
                // Deleting the account, then its dialog's two buttons, for anyone read (CLAUDE.md §8a).
                "account.delete",
                "account.delete_confirm",
                "account.delete_cancel",
            ),
            elementsTapped {
                @Suppress("DEPRECATION")
                CompositionLocalProvider(LocalClipboardManager provides RecordingClipboard()) {
                    AboutScreen(AppVersion("1.0.0", 10000), accountId = "p1") {
                        DeleteAccount(AccountState(stats = GUEST, submissions = emptyList()), NoAccountActions)
                    }
                }
            },
        )
        assertTrue(uris.opened.isNotEmpty())
    }

    @Test
    fun `every tap on the update screen is reported`() {
        assertEquals(setOf("update.store"), elementsTapped { UpdateScreen(UpdateButton(UpdateWay.STORE) {}) })
        assertEquals(setOf("update.reload"), elementsTapped { UpdateScreen(UpdateButton(UpdateWay.RELOAD) {}) })
    }

    @Test
    fun `every tap on Play is reported`() {
        val asked = elementsTapped { Play(PlayUiState.Asking(QUESTION)) }
        val revealed = elementsTapped { Play(PlayUiState.Revealed(QUESTION, OUTCOME)) }
        val failed = elementsTapped { Play(PlayUiState.Failed(DomainError.NETWORK)) }

        // Share opens its dialog, whose switch for the results shows only once there are results; and the
        // points are the way to the shop (CLAUDE.md §8d, *Sharing*, *The shop*).
        val share = setOf("play.share", "share.send", "share.cancel", "play.points")
        // The question's menu in the row, and what it lists once open (CLAUDE.md §8d, *Reports*).
        val menu =
            setOf("question_menu.open", "question_menu.report", "question_menu.hide_question") +
                "question_menu.hide_author"
        assertEquals(
            setOf("play.card_a", "play.card_b", "play.like", "play.dislike", "play.skip") + share + menu,
            asked,
        )
        assertEquals(
            setOf("play.card_a", "play.card_b", "play.like", "play.dislike", "play.skip", "share.results") +
                share + menu,
            revealed,
        )
        assertEquals(setOf("play.try_again"), failed)
    }

    /** An approved question of the player's own shares, as the Play screen's does (CLAUDE.md §8d, *Sharing*). */
    @Test
    fun `every tap on the Question details screen is reported`() {
        val approved =
            Submission(
                id = "s1",
                optionA = "Fly",
                optionB = "Turn invisible",
                categories = setOf("FOOD"),
                status = SubmissionStatus.APPROVED,
                rejectionReason = null,
                submittedAt = Instant.parse("2026-09-25T10:00:00Z"),
                answerCount = 4,
                tally = Tally(votesA = 3, votesB = 1),
            )
        assertEquals(
            setOf("question.share", "share.results", "share.send", "share.cancel"),
            elementsTapped { QuestionDetailsScreen(submission = approved, categories = emptyList()) },
        )
        assertEquals(
            emptySet(),
            elementsTapped {
                QuestionDetailsScreen(
                    submission = approved.copy(status = SubmissionStatus.PENDING),
                    categories = emptyList(),
                )
            },
        )
    }

    /** Each reason a report may give is its own tap, named by the reason (CLAUDE.md §8d, *Reports*). */
    @Test
    fun `the question menu's every reason is reported by its name`() {
        val scene =
            ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
                CompositionLocalProvider(LocalAnalytics provides analytics) {
                    WyrTheme { WyrStrings(Language.DEFAULT) { QuestionMenu(enabled = true, onPick = {}) } }
                }
            }
        val menu = SerbianCyrillicStrings.playScreen.menu
        try {
            scene.settle()
            ReportReason.entries.forEach { reason ->
                scene.tap(menu.name)
                scene.tap(menu.report)
                scene.tap(menu.reason(reason))
            }
        } finally {
            scene.close()
        }

        val reasons =
            analytics
                .named(AnalyticsEvent.TAP)
                .filter { it.properties[AnalyticsProperty.ELEMENT] == "question_menu.reason" }
                .map { it.properties[AnalyticsProperty.REASON] }
        assertEquals(ReportReason.entries.map { it.name.lowercase() }, reasons)
    }

    /** A card answers before the reveal and goes on after it: the tap says which. */
    @Test
    fun `a card's tap says whether the question was answered`() {
        elementsTapped { Play(PlayUiState.Asking(QUESTION)) }
        elementsTapped { Play(PlayUiState.Revealed(QUESTION, OUTCOME)) }

        val answered =
            analytics
                .named(AnalyticsEvent.TAP)
                .filter { it.properties[AnalyticsProperty.ELEMENT] == "play.card_a" }
                .map { it.properties[AnalyticsProperty.ANSWERED] }
        assertEquals(listOf(false, true), answered)
    }

    @Test
    fun `every tap on Account is reported`() {
        val guest = elementsTapped { Account(AccountState(stats = GUEST, submissions = emptyList())) }
        val registered = elementsTapped { Account(AccountState(stats = REGISTERED, submissions = emptyList())) }
        val playGames = elementsTapped { Account(AccountState(stats = PLAY_GAMES, submissions = emptyList())) }
        val listed = elementsTapped { Account(AccountState(stats = REGISTERED, submissions = listOf(SUBMISSION))) }
        val unread =
            elementsTapped { Account(AccountState(failure = AccountFailure(AccountAction.LOAD, DomainError.NETWORK))) }
        val listUnread =
            elementsTapped {
                Account(
                    AccountState(
                        stats = REGISTERED,
                        listFailure = AccountFailure(AccountAction.LOAD, DomainError.NETWORK),
                    ),
                )
            }

        // The points on the card, the way to the shop (CLAUDE.md §8d, *The shop*), once a player is read.
        val points = setOf("account.points")
        val ask = setOf("my_questions.new_question", "my_questions.first_question")
        assertEquals(setOf("account.open_auth") + points, guest)
        assertEquals(ask + "account.log_out" + points, registered)
        // No Log out for a player registered by Play Games alone, nor a way to add a username.
        assertEquals(ask + points, playGames)
        assertEquals(setOf("my_questions.new_question", "my_questions.question", "account.log_out") + points, listed)
        assertEquals(setOf("account.try_again"), unread)
        assertEquals(
            setOf("my_questions.new_question", "my_questions.try_again", "account.log_out") + points,
            listUnread,
        )
    }

    /**
     * Each language in the menu is its own tap, named by its tag. The menu is off the Account screen for
     * now (CLAUDE.md §8f), drawn alone, so its names stay.
     */
    @Test
    fun `the language menu's every language is reported by its tag`() {
        assertEquals(
            setOf("language.menu", "language.option"),
            elementsTapped { LanguageMenu(selected = Language.DEFAULT, onSelect = {}) },
        )

        val picked =
            analytics
                .named(AnalyticsEvent.TAP)
                .filter { it.properties[AnalyticsProperty.ELEMENT] == "language.option" }
                .map { it.properties[AnalyticsProperty.LANGUAGE] }
        assertEquals(Language.OFFERED.map { it.tag }.toSet(), picked.toSet())
    }

    @Test
    fun `every tap on the Auth page is reported`() {
        val typed = AccountState(stats = GUEST, registerUsername = "bob_1", registerPassword = "correct horse")
        val register = elementsTapped { AuthScreen(state = typed, actions = NoAccountActions) }
        val logIn =
            elementsTapped {
                AuthScreen(
                    state = typed.copy(authMode = AuthMode.LOG_IN, loginUsername = "bob_1", loginPassword = "horse"),
                    actions = NoAccountActions,
                )
            }
        val warned =
            elementsTapped {
                AuthScreen(
                    state =
                        typed.copy(
                            authMode = AuthMode.LOG_IN,
                            loginUsername = "bob_1",
                            loginPassword = "horse",
                            guestPointsWarning = 12,
                        ),
                    actions = NoAccountActions,
                )
            }
        val unread =
            elementsTapped {
                AuthScreen(
                    state = AccountState(failure = AccountFailure(AccountAction.LOAD, DomainError.NETWORK)),
                    actions = NoAccountActions,
                )
            }

        val playGames =
            elementsTapped { AuthScreen(state = typed.copy(playGamesAvailable = true), actions = NoAccountActions) }

        // The Register form's line links the terms and the privacy policy (CLAUDE.md §8d).
        val terms = setOf("auth.terms", "auth.privacy")
        assertEquals(setOf("auth.show_password", "auth.register", "auth.to_log_in") + terms, register)
        assertEquals(
            setOf("auth.play_games", "auth.show_password", "auth.register", "auth.to_log_in") + terms,
            playGames,
        )
        assertEquals(setOf("auth.log_in", "auth.to_register"), logIn)
        assertEquals(setOf("auth.log_in_anyway", "auth.cancel", "auth.to_register"), warned)
        assertEquals(setOf("auth.try_again", "auth.show_password", "auth.to_log_in") + terms, unread)
    }

    @Test
    fun `every tap on the Submit form is reported`() {
        val written =
            SubmitState(
                optionA = "Fly",
                optionB = "Turn invisible",
                categories = setOf("FOOD"),
                categoryOptions = CATEGORIES,
                points = 5,
                registered = true,
            )
        val form = elementsTapped { SubmitScreen(state = written, actions = NoSubmitActions) }
        val unread =
            elementsTapped {
                SubmitScreen(
                    state = written.copy(categoriesFailure = SubmitFailure(DomainError.NETWORK)),
                    actions = NoSubmitActions,
                )
            }
        val pointsUnread =
            elementsTapped {
                SubmitScreen(
                    state = written.copy(pointsFailure = SubmitFailure(DomainError.NETWORK)),
                    actions = NoSubmitActions,
                )
            }

        // The line under Send links the question rules (CLAUDE.md §8d, *Submitting*).
        val rules = setOf("submit.rules")
        // And after the categories, the chip saying none fits (CLAUDE.md §8d, *Categories*, *Nothing fits*).
        val categories = setOf("submit.category", "submit.nothing_fits")
        assertEquals(categories + setOf("submit.send") + rules, form)
        assertEquals(categories + setOf("submit.categories_try_again", "submit.send") + rules, unread)
        assertEquals(categories + setOf("submit.send", "submit.try_again") + rules, pointsUnread)
        val chips =
            analytics.named(AnalyticsEvent.TAP).filter {
                it.properties[AnalyticsProperty.ELEMENT] ==
                    "submit.category"
            }
        assertEquals(CATEGORIES.map { it.id }.toSet(), chips.map { it.properties[AnalyticsProperty.CATEGORY] }.toSet())
    }

    @Test
    fun `every tap on the Categories screen is reported`() {
        val state = CategoriesState(ticked = setOf("FOOD"), categories = CATEGORIES, found = CATEGORIES)
        val listed = elementsTapped { CategoriesScreen(state = state, actions = NoCategoriesActions) }
        val unread =
            elementsTapped {
                CategoriesScreen(state = state.copy(failure = DomainError.NETWORK), actions = NoCategoriesActions)
            }

        assertEquals(setOf("categories.all", "categories.category", "categories.play"), listed)
        assertEquals(setOf("categories.try_again", "categories.all", "categories.category", "categories.play"), unread)
    }

    @Test
    fun `every tap in the shop is reported`() {
        val themes = listOf(ShopTheme("NEON_NIGHT", 220, owned = true), ShopTheme("OCEAN", 220, owned = false))
        val registered = ShopState(shop = Shop(themes, points = 500, registered = true))
        val guest = ShopState(shop = Shop(themes.map { it.copy(owned = false) }, points = 500, registered = false))

        // The picker's tiles, the game's own and Neon night, and Ocean's Buy; the dialog is its state's.
        assertEquals(setOf("shop.apply", "shop.buy"), elementsTapped { Shop(registered) })
        assertEquals(
            setOf("shop.apply", "shop.buy", "shop.buy_confirm", "shop.buy_cancel"),
            elementsTapped { Shop(registered.copy(confirming = "OCEAN")) },
        )
        // A guest's every Buy is off, and the way to register is on; the game's own is theirs to put on.
        assertEquals(setOf("shop.apply", "shop.open_auth"), elementsTapped { Shop(guest) })
        assertEquals(
            setOf("shop.try_again"),
            elementsTapped { Shop(ShopState(readFailure = ShopFailure(DomainError.NETWORK))) },
        )
    }

    /**
     * Draws [content] and taps everything that can be tapped on it, and then on what the taps
     * brought up (the language menu's list), and returns the elements the taps reported.
     */
    private fun elementsTapped(
        skipping: Int? = null,
        content: @Composable () -> Unit,
    ): Set<String> {
        val scene =
            ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
                CompositionLocalProvider(LocalAnalytics provides analytics, LocalUriHandler provides uris) {
                    WyrTheme { WyrStrings(Language.DEFAULT) { content() } }
                }
            }
        val reported = mutableSetOf<String>()
        val done = mutableSetOf<String>()
        try {
            scene.settle()
            // Twice: what the first taps bring up is tapped too.
            repeat(2) {
                // [skipping], the first time round, is left alone: the index of a node from the top.
                val nodes = scene.tappable().filterIndexed { index, _ -> it > 0 || index != skipping }
                nodes.filter { signatureOf(it) !in done }.forEach { node ->
                    done += signatureOf(node)
                    val before = analytics.named(AnalyticsEvent.TAP).size
                    val tap = assertNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action, signatureOf(node))
                    tap()
                    scene.settle()
                    val taps = analytics.named(AnalyticsEvent.TAP).drop(before)
                    assertEquals(1, taps.size, "a tap on ${signatureOf(node)} reported $taps")
                    val element = taps.single().properties[AnalyticsProperty.ELEMENT] as? String
                    assertTrue(element != null && ELEMENT_NAME.matches(element), "\"$element\" is no element's name")
                    reported += element
                }
            }
        } finally {
            scene.close()
        }
        return reported
    }

    /** Everything a player could tap: whatever takes a click and is on, but a text field. */
    private fun ImageComposeScene.tappable(): List<SemanticsNode> =
        nodes().filter { node ->
            node.config.getOrNull(SemanticsActions.OnClick)?.action != null &&
                node.config.getOrNull(SemanticsProperties.Disabled) == null &&
                node.config.getOrNull(SemanticsActions.SetText) == null
        }

    private fun signatureOf(node: SemanticsNode): String = "${node.texts}${node.descriptions}@${node.positionInRoot}"

    @Composable
    private fun Shop(state: ShopState) {
        ShopScreen(state = state, actions = NoShopActions, worn = GameThemes.Default, onWear = {}, onOpenAuth = {})
    }

    private object NoShopActions : ShopActions {
        override fun refresh() = Unit

        override fun askToBuy(themeId: String) = Unit

        override fun cancelBuy() = Unit

        override fun buy() = Unit

        override fun boughtWorn() = Unit
    }

    @Composable
    private fun Play(state: PlayUiState) {
        PlayScreen(state = state, points = 5, onChoose = {}, onSkip = {}, onNext = {}, onReact = {}, onRetry = {})
    }

    @Composable
    private fun Account(state: AccountState) {
        AccountScreen(
            state = state,
            actions = NoAccountActions,
            environment = WyrEnvironment.PROD,
            onOpenAuth = {},
            onNewQuestion = {},
        )
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

    private object NoSubmitActions : SubmitActions {
        override fun refresh() = Unit

        override fun leftForm() = Unit

        override fun setOptionA(text: String) = Unit

        override fun setOptionB(text: String) = Unit

        override fun toggleCategory(id: String) = Unit

        override fun toggleNothingFits() = Unit

        override fun setCategorySuggestion(text: String) = Unit

        override fun submit() = Unit
    }

    private object NoCategoriesActions : CategoriesActions {
        override fun search(query: String) = Unit

        override fun toggle(id: String) = Unit

        override fun selectAll() = Unit

        override fun refresh() = Unit

        override fun play() = Unit
    }

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 900

        /** `screen.what`, in lower case and underscores. */
        val ELEMENT_NAME = Regex("[a-z_]+\\.[a-z_]+")

        val QUESTION = Question(id = "q1", optionA = "Fly", optionB = "Turn invisible", categories = setOf("FOOD"))
        val OUTCOME =
            VoteOutcome(yourSide = Side.A, tally = Tally(votesA = 7, votesB = 3), pointsAwarded = 1, totalPoints = 6)
        val GUEST = PlayerStats(totalPoints = 5, questionsAnswered = 5)
        val REGISTERED = PlayerStats(totalPoints = 5, questionsAnswered = 5, username = "bob_1")
        val PLAY_GAMES = PlayerStats(totalPoints = 5, questionsAnswered = 5, playGamesLinked = true)
        val CATEGORIES =
            listOf(
                Category(id = "FOOD", nameSr = "Храна", nameEn = "Food"),
                Category(id = "TRAVEL", nameSr = "Путовања", nameEn = "Travel"),
            )
        val SUBMISSION =
            Submission(
                id = "s1",
                optionA = "Fly",
                optionB = "Turn invisible",
                categories = setOf("FOOD"),
                status = SubmissionStatus.PENDING,
                rejectionReason = null,
                submittedAt = Instant.fromEpochMilliseconds(0),
            )
    }
}
