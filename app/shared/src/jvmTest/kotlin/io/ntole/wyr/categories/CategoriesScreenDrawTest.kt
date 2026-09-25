package io.ntole.wyr.categories

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.descriptions
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.WyrStrings
import io.ntole.wyr.language.categoryName
import io.ntole.wyr.language.fill
import io.ntole.wyr.language.stringsOf
import io.ntole.wyr.nodes
import io.ntole.wyr.settle
import io.ntole.wyr.tap
import io.ntole.wyr.texts
import io.ntole.wyr.theme.WyrTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Categories screen (CLAUDE.md §8d, *Categories*) drawn off screen at two phones' sizes, in each
 * theme and each language, from every state it can be in, and read through its semantics: what it
 * shows, in what order, what each line and button does, and that at a short phone's size with
 * hundreds of categories the search field and Play stay on screen while the list scrolls.
 */
class CategoriesScreenDrawTest {
    @Test
    fun `the screen draws in every state in both themes and every language`() {
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
     * An iPhone SE (667 high) less its status bar (20) and the top bar above the screen (48), with
     * hundreds of categories, some of them ticked, and the failure line shown, the most the screen's
     * fixed parts take. Its lazy list is not measured the way the other screens' tests measure theirs,
     * so this asks where things are drawn: the search field and Play on screen, nothing cut short, and
     * only the lines that fit composed.
     */
    @Test
    fun `the screen fits a short phone with hundreds of categories`() {
        Language.entries.forEach { language ->
            val shared = stringsOf(language)
            val strings = shared.categoriesScreen
            val state =
                CategoriesState(ticked = MANY_TICKED, categories = MANY, found = MANY, failure = DomainError.NETWORK)
            val scene = scene(state, language)
            try {
                val play = assertNotNull(scene.nodes().singleOrNull { shared.play in it.texts }, "$language")
                assertTrue(play.boundsInRoot.bottom <= SHORT_PHONE_HEIGHT, "$language: Play at ${play.boundsInRoot}")
                assertTrue(play.boundsInRoot.right <= SHORT_PHONE_WIDTH, "$language: Play at ${play.boundsInRoot}")
                assertTrue(scene.searchField().boundsInRoot.top >= 0f, "$language")
                assertTrue(strings.selectedCount.fill(MANY_TICKED.size) in scene.allTexts(), "$language")
                assertTrue(shared.tryAgain in scene.texts(), "$language")
                assertEquals(emptyList(), scene.cutShort(), "$language")

                val shown = MANY.count { categoryName(it, language) in scene.texts() }
                assertTrue(shown in 1 until MANY.size, "$language composes $shown of ${MANY.size} lines")
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun `the list scrolls to its last category and Play stays on screen`() {
        val language = Language.SERBIAN_LATIN
        val scene = scene(CategoriesState(categories = MANY, found = MANY), language)
        try {
            val last = categoryName(MANY.last(), language)
            assertFalse(last in scene.texts(), "the last line is composed before it is scrolled to")

            val list = scene.nodes().single { it.config.getOrNull(SemanticsActions.ScrollToIndex) != null }
            // All is the list's first line, so the last category is one past the categories' own count.
            assertNotNull(list.config.getOrNull(SemanticsActions.ScrollToIndex)?.action).invoke(MANY.size)
            scene.settle()

            assertTrue(last in scene.texts(), "${scene.texts()}")
            val play = scene.nodes().single { stringsOf(language).play in it.texts }
            assertTrue(play.boundsInRoot.bottom <= SHORT_PHONE_HEIGHT)
        } finally {
            scene.close()
        }
    }

    /** The search field's hint, All, every category in the language shown, and Play, top to bottom. */
    @Test
    fun `the screen shows the search then All then every category then Play`() {
        Language.entries.forEach { language ->
            val shared = stringsOf(language)
            val scene =
                scene(CategoriesState(categories = KNOWN, found = KNOWN), language, width = WIDTH, height = HEIGHT)
            try {
                val names = KNOWN.map { categoryName(it, language) }
                val expected = listOf(shared.categoriesScreen.search, shared.allCategories) + names + shared.play
                assertEquals(expected, scene.allTexts(), "$language")
                assertEquals(emptyList(), scene.descriptions(), "$language")
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun `the categories are named in the language shown`() {
        val names =
            mapOf(
                Language.SERBIAN_CYRILLIC to listOf("Храна", "Начин живота", "Етика", "Супермоћи", "Апсурдно"),
                Language.SERBIAN_LATIN to listOf("Hrana", "Način života", "Etika", "Supermoći", "Apsurdno"),
                Language.ENGLISH to listOf("Food", "Lifestyle", "Ethics", "Superpowers", "Absurd"),
            )
        names.forEach { (language, expected) ->
            val scene =
                scene(CategoriesState(categories = KNOWN, found = KNOWN), language, width = WIDTH, height = HEIGHT)
            try {
                assertEquals(expected, scene.texts().filter { it in expected }, "$language")
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun `All is ticked while no category is and a ticked category unticks it`() {
        val language = Language.ENGLISH
        val none = scene(CategoriesState(categories = KNOWN, found = KNOWN), language)
        try {
            assertEquals(ToggleableState.On, none.toggleOf("All"))
            assertEquals(ToggleableState.Off, none.toggleOf("Food"))
        } finally {
            none.close()
        }
        val some = scene(CategoriesState(ticked = setOf("FOOD", "ETHICS"), categories = KNOWN, found = KNOWN), language)
        try {
            assertEquals(ToggleableState.Off, some.toggleOf("All"))
            assertEquals(ToggleableState.On, some.toggleOf("Food"))
            assertEquals(ToggleableState.On, some.toggleOf("Ethics"))
            assertEquals(ToggleableState.Off, some.toggleOf("Absurd"))
        } finally {
            some.close()
        }
    }

    /** Nothing beside Play while All is ticked; the count once a category is, in each language. */
    @Test
    fun `how many categories are ticked shows beside Play`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).categoriesScreen
            val none = scene(CategoriesState(categories = KNOWN, found = KNOWN), language)
            try {
                assertFalse(strings.selectedCount.fill(0) in none.allTexts(), "$language: ${none.allTexts()}")
            } finally {
                none.close()
            }
            val two =
                scene(CategoriesState(ticked = setOf("FOOD", "ETHICS"), categories = KNOWN, found = KNOWN), language)
            try {
                assertTrue(strings.selectedCount.fill(2) in two.allTexts(), "$language: ${two.allTexts()}")
            } finally {
                two.close()
            }
        }
        assertEquals("Изабрано: 2", stringsOf(Language.SERBIAN_CYRILLIC).categoriesScreen.selectedCount.fill(2))
        assertEquals("Izabrano: 2", stringsOf(Language.SERBIAN_LATIN).categoriesScreen.selectedCount.fill(2))
        assertEquals("Selected: 2", stringsOf(Language.ENGLISH).categoriesScreen.selectedCount.fill(2))
    }

    @Test
    fun `a search that finds nothing says so under All`() {
        Language.entries.forEach { language ->
            val shared = stringsOf(language)
            val scene = scene(CategoriesState(query = "xyz", categories = KNOWN, found = emptyList()), language)
            try {
                // The search field shows what is typed, not its hint, and says it as no text of its own.
                val expected = listOf(shared.allCategories, shared.categoriesScreen.noMatch, shared.play)
                assertEquals(expected, scene.texts(), "$language")
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun `the categories being read with none read before show a named spinner`() {
        Language.entries.forEach { language ->
            val shared = stringsOf(language)
            val scene = scene(CategoriesState(isLoading = true), language)
            try {
                assertEquals(listOf(shared.loading), scene.descriptions(), "$language")
                assertFalse(shared.categoriesScreen.noMatch in scene.texts(), "$language")
            } finally {
                scene.close()
            }
        }
    }

    /** Offline as the Play screen says it, anything else as the Submit form does, beside the game's Try again. */
    @Test
    fun `a failed read says so above the list with Try again`() {
        Language.entries.forEach { language ->
            val shared = stringsOf(language)
            val failures =
                mapOf(
                    DomainError.NETWORK to shared.playScreen.cannotReach,
                    DomainError.SERVER to shared.categoriesUnread,
                )
            failures.forEach { (failure, said) ->
                listOf(emptyList(), KNOWN).forEach { known ->
                    val state = CategoriesState(categories = known, found = known, failure = failure)
                    val scene = scene(state, language)
                    try {
                        // One line, the button's text drawn a little higher than the failure's: either first.
                        val texts = scene.texts().filter { it != shared.categoriesScreen.search }
                        assertEquals(setOf(said, shared.tryAgain), texts.take(2).toSet(), "$failure in $language")
                        assertEquals(shared.allCategories, texts[2], "$language")
                        val names = known.map { categoryName(it, language) }
                        assertEquals(names, texts.filter { it in names }, "$language")
                    } finally {
                        scene.close()
                    }
                }
            }
        }
        assertEquals("Игра није доступна.", unreadText(DomainError.NETWORK, stringsOf(Language.SERBIAN_CYRILLIC)))
        assertEquals("Kategorije nisu učitane.", unreadText(DomainError.SERVER, stringsOf(Language.SERBIAN_LATIN)))
        assertEquals("Couldn't load the categories.", unreadText(DomainError.SERVER, stringsOf(Language.ENGLISH)))
    }

    @Test
    fun `every line and button does what it says`() {
        Language.entries.forEach { language ->
            val shared = stringsOf(language)
            val actions = RecordedActions()
            val state = CategoriesState(categories = KNOWN, found = KNOWN, failure = DomainError.SERVER)
            val scene = scene(state, language, actions = actions)
            try {
                scene.tap(categoryName(KNOWN[2], language))
                scene.tap(shared.allCategories)
                scene.tap(shared.tryAgain)
                scene.type("хр")
                scene.tap(shared.play)

                assertEquals(
                    listOf("toggle ETHICS", "selectAll", "refresh", "search хр", "play"),
                    actions.calls,
                    "$language",
                )
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun `nothing can be ticked or played again while Play is sent`() {
        val language = Language.SERBIAN_CYRILLIC
        val strings = stringsOf(language)
        val state = CategoriesState(ticked = setOf("FOOD"), categories = KNOWN, found = KNOWN, isPlaying = true)
        val scene = scene(state, language)
        try {
            val offs = listOf(strings.allCategories, "Храна", "Етика", strings.play)
            offs.forEach { text ->
                val node = scene.nodes().single { text in it.texts }
                assertTrue(node.config.contains(SemanticsProperties.Disabled), "$text is on")
            }
        } finally {
            scene.close()
        }
    }

    private class RecordedActions : CategoriesActions {
        val calls = mutableListOf<String>()

        override fun search(query: String) {
            calls += "search $query"
        }

        override fun toggle(id: String) {
            calls += "toggle $id"
        }

        override fun selectAll() {
            calls += "selectAll"
        }

        override fun refresh() {
            calls += "refresh"
        }

        override fun play() {
            calls += "play"
        }
    }

    private fun scene(
        state: CategoriesState,
        language: Language,
        dark: Boolean = false,
        width: Int = SHORT_PHONE_WIDTH,
        height: Int = SHORT_PHONE_HEIGHT,
        actions: CategoriesActions = RecordedActions(),
    ): ImageComposeScene =
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            WyrTheme(darkTheme = dark) {
                WyrStrings(language) { CategoriesScreen(state = state, actions = actions) }
            }
        }.also { it.settle() }

    /** Every node, each on its own as a screen reader never reads them: a text field's hint among them. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun ImageComposeScene.unmerged(): List<SemanticsNode> =
        semanticsOwners
            .flatMap { owner -> owner.getAllSemanticsNodes(mergingEnabled = false) }
            .sortedWith(compareBy({ it.positionInRoot.y }, { it.positionInRoot.x }))

    /** Every text drawn, the search field's hint included, from the top down. */
    private fun ImageComposeScene.allTexts(): List<String> = unmerged().flatMap { it.texts }

    /** Every text drawn that is cut short with an ellipsis, or taller than the height it was given. */
    private fun ImageComposeScene.cutShort(): List<String> =
        unmerged()
            .filter { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                node.config
                    .getOrNull(SemanticsActions.GetTextLayoutResult)
                    ?.action
                    ?.invoke(layouts)
                // Not hasVisualOverflow: the layout this hands back is laid out again at the width the
                // text was offered, not the one it took, so every text would read as too wide.
                layouts.any { layout ->
                    layout.didOverflowHeight ||
                        (0 until layout.lineCount).any(layout::isLineEllipsized)
                }
            }.flatMap { it.texts }

    private fun ImageComposeScene.searchField(): SemanticsNode =
        nodes().single { it.config.getOrNull(SemanticsActions.SetText) != null }

    /** Types [text] into the search field, as the keyboard would. */
    private fun ImageComposeScene.type(text: String) {
        assertNotNull(searchField().config.getOrNull(SemanticsActions.SetText)?.action).invoke(AnnotatedString(text))
        settle()
    }

    /** Whether the line showing [text] is ticked. */
    private fun ImageComposeScene.toggleOf(text: String): ToggleableState? =
        nodes().single { text in it.texts }.config.getOrNull(SemanticsProperties.ToggleableState)

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 900

        /** An iPhone SE (667 high) less its status bar (20) and the top bar above the screen (48). */
        const val SHORT_PHONE_WIDTH = 375
        const val SHORT_PHONE_HEIGHT = 599

        /** The server's first five categories, as V6 wrote them, in the order of categories. */
        val KNOWN =
            listOf(
                Category(id = "FOOD", nameSr = "Храна", nameEn = "Food"),
                Category(id = "LIFESTYLE", nameSr = "Начин живота", nameEn = "Lifestyle"),
                Category(id = "ETHICS", nameSr = "Етика", nameEn = "Ethics"),
                Category(id = "SUPERPOWERS", nameSr = "Супермоћи", nameEn = "Superpowers"),
                Category(id = "ABSURD", nameSr = "Апсурдно", nameEn = "Absurd"),
            )

        /**
         * The hundreds planned (§8d, *Categories*), first a name as long as the server takes, 39 of its
         * 40, which wraps rather than being cut short.
         */
        val MANY: List<Category> =
            listOf(Category("LONG", "Научна фантастика и путовања кроз време", "Science fiction and time travel")) +
                (1..300).map { n -> Category(id = "C$n", nameSr = "Категорија $n", nameEn = "Category $n") }

        val MANY_TICKED: Set<String> = MANY.take(120).map { it.id }.toSet()

        val STATES: List<CategoriesState> =
            listOf(
                CategoriesState(isLoading = true),
                CategoriesState(failure = DomainError.NETWORK),
                CategoriesState(categories = KNOWN, found = KNOWN),
                CategoriesState(categories = KNOWN, found = KNOWN, isLoading = true),
                CategoriesState(ticked = setOf("ETHICS", "FOOD"), categories = KNOWN, found = KNOWN),
                CategoriesState(query = "хр", categories = KNOWN, found = KNOWN.take(1)),
                CategoriesState(query = "xyz", categories = KNOWN, found = emptyList()),
                CategoriesState(categories = KNOWN, found = KNOWN, failure = DomainError.SERVER),
                CategoriesState(ticked = setOf("FOOD"), categories = KNOWN, found = KNOWN, isPlaying = true),
                CategoriesState(ticked = MANY_TICKED, categories = MANY, found = MANY),
            )
    }
}
