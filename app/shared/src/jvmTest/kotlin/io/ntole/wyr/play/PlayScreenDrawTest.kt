package io.ntole.wyr.play

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.core.domain.vote.VoteOutcome
import io.ntole.wyr.descriptions
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.PlayStrings
import io.ntole.wyr.language.WyrStrings
import io.ntole.wyr.language.stringsOf
import io.ntole.wyr.nodes
import io.ntole.wyr.sizeNeeded
import io.ntole.wyr.tap
import io.ntole.wyr.texts
import io.ntole.wyr.theme.WyrTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Play screen (CLAUDE.md §8d, *The Play screen*) drawn off screen at two phones' sizes, in each
 * theme and each language, from every state it can be in, with every category played or a few, and
 * with the category picker open; read through its semantics, as a screen reader reads it, and
 * tapped through them. Compose measures and draws it all, so a layout that cannot be measured fails
 * here rather than when the screen opens. Whether what it draws fits is asked separately, since a
 * squeezed card draws, and so is whether the picker is drawn at all, since the screen draws without
 * it too.
 */
class PlayScreenDrawTest {
    @Test
    fun `the screen draws in every state in both themes and every language`() {
        statesOf(QUESTION).forEach { state ->
            listOf(false, true).forEach { dark ->
                Language.entries.forEach { language ->
                    draw(state, dark, WIDTH, HEIGHT, language = language)
                    draw(state, dark, SHORT_PHONE_WIDTH, SHORT_PHONE_HEIGHT, language = language)
                }
            }
        }
    }

    @Test
    fun `the screen draws with every selection played`() {
        statesOf(QUESTION).forEach { state ->
            SELECTIONS.forEach { categories ->
                listOf(false, true).forEach { dark ->
                    draw(state, dark, SHORT_PHONE_WIDTH, SHORT_PHONE_HEIGHT, categories = categories)
                }
            }
        }
    }

    /** The picker opens over whatever the screen shows, Play off while the screen cannot take a change. */
    @Test
    fun `the screen draws with the category picker open`() {
        statesOf(QUESTION).forEach { state ->
            listOf(false, true).forEach { dark ->
                SELECTIONS.forEach { ticked ->
                    draw(state, dark, WIDTH, HEIGHT, picking = ticked)
                    draw(state, dark, SHORT_PHONE_WIDTH, SHORT_PHONE_HEIGHT, picking = ticked)
                }
            }
        }
    }

    /**
     * Drawing cannot see a dialog that never opens, since the screen draws without it too, and there
     * is no Compose UI test library in the tree to look for it. So this compares pixels: the screen
     * must draw differently with the picker open, and differently again with a category ticked in
     * it. Two draws of one screen are the same pixels, which is what makes a difference mean one.
     */
    @Test
    fun `the category picker opens over the screen with what it has ticked`() {
        statesOf(QUESTION).forEach { state ->
            val closed = pixels(state, picking = null)
            assertContentEquals(closed, pixels(state, picking = null), "$state drawn twice")
            val open = pixels(state, picking = emptySet())
            assertFalse(closed.contentEquals(open), "$state draws the same with the picker open")
            assertFalse(
                open.contentEquals(pixels(state, picking = setOf(Category.ETHICS))),
                "$state's picker draws the same with Ethics ticked",
            )
        }
    }

    /**
     * Height the screen needs and does not get comes out of the weighted option cards, which then
     * cut off what they hold, the reveal's percentages first. Drawing cannot see that, so this asks
     * the screen how much height it needs at an iPhone SE's height, in every language, on a question
     * of two lines an option and on one of one line.
     *
     * Where text wraps differs a lot from one font to another, and CI's Linux has wider fonts than a
     * phone (Noto Sans or DejaVu Sans), so it is measured at the width drawn above, 25 more than 375.
     */
    @Test
    fun `every state fits a short phone without squeezing the option cards`() {
        (statesOf(QUESTION) + statesOf(ONE_LINE_QUESTION)).forEach { state ->
            Language.entries.forEach { language ->
                SELECTIONS.forEach { categories ->
                    val needed = heightNeeded(state, WIDTH, categories, language)
                    assertTrue(
                        needed <= SHORT_PHONE_HEIGHT,
                        "$state in $language on $categories needs $needed of $SHORT_PHONE_HEIGHT",
                    )
                }
            }
        }
    }

    /** However many categories are played, they take the row's one line, cut short rather than wrapped. */
    @Test
    fun `every category played takes no more height than none`() {
        statesOf(QUESTION).forEach { state ->
            listOf(WIDTH, SHORT_PHONE_WIDTH).forEach { width ->
                assertEquals(
                    heightNeeded(state, width, categories = emptySet()),
                    heightNeeded(state, width, categories = Category.selectable.toSet()),
                    "$state at $width wide",
                )
            }
        }
    }

    /** Every category and All on one card, with Play under them, without scrolling. */
    @Test
    fun `the category picker fits a short phone`() {
        SELECTIONS.forEach { ticked ->
            val needed =
                heightNeeded(SHORT_PHONE_WIDTH) {
                    CategoryPicker(
                        ticked = ticked,
                        canApply = true,
                        onToggle = {},
                        onSelectAll = {},
                        onApply = {},
                        onClose = {},
                    )
                }
            assertTrue(needed <= SHORT_PHONE_HEIGHT, "the picker on $ticked needs $needed of $SHORT_PHONE_HEIGHT")
        }
    }

    /**
     * A failed like says so in the points' place, so no line of its own moves the cards; and at any
     * font size, since text grows with the phone's font size and the heart's touch target does not.
     */
    @Test
    fun `a failed like takes no height`() {
        val asked = PlayUiState.Asking(QUESTION)
        val revealed = PlayUiState.Revealed(QUESTION, OUTCOME)
        FONT_SCALES.forEach { fontScale ->
            Language.entries.forEach { language ->
                LIKE_FAILURES.forEach { error ->
                    val at = "in $language at font scale $fontScale"
                    assertEquals(
                        heightNeeded(asked, WIDTH, language = language, fontScale = fontScale),
                        heightNeeded(asked.copy(likeError = error), WIDTH, language = language, fontScale = fontScale),
                        "asked with $error $at",
                    )
                    assertEquals(
                        heightNeeded(revealed, WIDTH, language = language, fontScale = fontScale),
                        heightNeeded(
                            revealed.copy(likeError = error),
                            WIDTH,
                            language = language,
                            fontScale = fontScale,
                        ),
                        "revealed with $error $at",
                    )
                }
            }
        }
    }

    /**
     * What each state shows, and nothing else: no title, no *OR*, no *+1*, no verdict, no vote
     * counts and no Next question; Skip, named for a screen reader, only while a question is asked.
     * Read once the reveal has counted up.
     */
    @Test
    fun `every state shows its texts and nothing else in every language`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).playScreen
            expectedOf(strings, stringsOf(language).points(POINTS)).forEach { (state, expected) ->
                val (texts, names) = expected
                withScreen(state, language = language) { scene, _ ->
                    scene.renderAt(COUNTED_UP)
                    assertEquals(texts.sorted(), scene.texts().sorted(), "$state in $language")
                    assertEquals(names, scene.descriptions(), "$state in $language")
                }
            }
            // The points are the server's, and there are none until it has said.
            withScreen(PlayUiState.Asking(QUESTION), points = null, language = language) { scene, _ ->
                assertEquals(
                    listOf(QUESTION.optionA, strings.allCategories, "0", QUESTION.optionB).sorted(),
                    scene.texts().sorted(),
                    "in $language",
                )
            }
        }
    }

    @Test
    fun `a card answers before the reveal and goes on to the next question after it`() {
        withScreen(PlayUiState.Asking(QUESTION)) { scene, actions ->
            scene.tap(QUESTION.optionA)
            scene.tap(QUESTION.optionB)
            assertEquals(listOf("choose A", "choose B"), actions.tapped, "before the reveal")
        }
        withScreen(PlayUiState.Revealed(QUESTION, OUTCOME)) { scene, actions ->
            scene.tap(QUESTION.optionA)
            scene.tap(QUESTION.optionB)
            assertEquals(listOf("next", "next"), actions.tapped, "after the reveal")
        }
    }

    /** One action at a time: while a vote or a like is in flight, the cards, the heart and Skip are off. */
    @Test
    fun `the cards and the heart and Skip are off while anything is in flight`() {
        val strings = stringsOf(Language.DEFAULT).playScreen
        val parts = listOf(QUESTION.optionA, QUESTION.optionB, strings.like)
        listOf(
            PlayUiState.Asking(QUESTION, isSubmitting = true),
            PlayUiState.Asking(QUESTION, isLiking = true),
            PlayUiState.Revealed(QUESTION, OUTCOME, isLiking = true),
        ).forEach { state ->
            val off = if (state is PlayUiState.Asking) parts + strings.skip else parts
            withScreen(state) { scene, _ -> off.forEach { assertTrue(scene.node(it).isOff, "$it in $state") } }
        }
        listOf(PlayUiState.Asking(QUESTION), PlayUiState.Revealed(QUESTION, OUTCOME)).forEach { state ->
            val on = if (state is PlayUiState.Asking) parts + strings.skip else parts
            withScreen(state) { scene, _ -> on.forEach { assertFalse(scene.node(it).isOff, "$it in $state") } }
        }
    }

    /**
     * Skip is in the row between the cards (CLAUDE.md §8d, *Skipping*), after the heart, while a
     * question is asked, in every language, and a tap on it skips; once the answer is revealed it is
     * gone, since a card is then the way on.
     */
    @Test
    fun `Skip is in the row while a question is asked and gone once it is answered`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).playScreen
            withScreen(PlayUiState.Asking(QUESTION), language = language) { scene, actions ->
                val cardA = scene.node(QUESTION.optionA).boundsInRoot
                val cardB = scene.node(QUESTION.optionB).boundsInRoot
                val skip = scene.node(strings.skip).boundsInRoot
                assertTrue(skip.center.y > cardA.bottom && skip.center.y < cardB.top, "Skip is at $skip in $language")
                assertTrue(skip.left > scene.node(strings.like).boundsInRoot.right, "Skip is not after the heart")

                scene.tap(strings.skip)

                assertEquals(listOf("skip"), actions.tapped, "in $language")
            }
            withScreen(PlayUiState.Revealed(QUESTION, OUTCOME), language = language) { scene, _ ->
                assertFalse(strings.skip in scene.descriptions(), "Skip on the reveal in $language")
            }
        }
    }

    /**
     * Skip's place is kept once it is gone, so the reveal moves nothing in the row: not the
     * categories, not the points, not the heart the player may tap next.
     */
    @Test
    fun `the row does not move when the answer is revealed`() {
        val strings = stringsOf(Language.DEFAULT).playScreen
        val parts = listOf(strings.allCategories, POINTS_SHOWN, strings.like, "0")
        val asked = mutableListOf<Rect>()
        withScreen(PlayUiState.Asking(QUESTION)) { scene, _ -> parts.mapTo(asked) { scene.node(it).boundsInRoot } }
        withScreen(PlayUiState.Revealed(QUESTION, OUTCOME)) { scene, _ ->
            assertEquals(asked, parts.map { scene.node(it).boundsInRoot })
        }
    }

    @Test
    fun `the row between the cards opens the categories and likes the question`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).playScreen
            statesOf(QUESTION).filterIsInstance<PlayUiState.OnQuestion>().forEach { state ->
                withScreen(state, language = language) { scene, actions ->
                    val liked = if (state.question.likedByMe) ToggleableState.On else ToggleableState.Off
                    val heart = scene.node(strings.like)
                    assertEquals(liked, heart.config.getOrNull(SemanticsProperties.ToggleableState), "$state")
                    assertTrue(state.question.likeCount.toString() in scene.texts(), "$state")

                    scene.tap(strings.allCategories)
                    scene.tap(strings.like)

                    assertEquals(listOf("categories", "like"), actions.tapped, "$state in $language")
                }
            }
        }
    }

    /**
     * A screen reader hears what a tap does where the text does not say it: a card on the reveal goes
     * on to the next question, which its option alone would make sound like answering again, and the
     * categories played open the picker. Before the reveal a card's option says it all.
     */
    @Test
    fun `a screen reader hears what a tap on a revealed card and on the categories does`() {
        val cards = listOf(QUESTION.optionA, QUESTION.optionB)
        Language.entries.forEach { language ->
            val strings = stringsOf(language).playScreen
            withScreen(PlayUiState.Asking(QUESTION), language = language) { scene, _ ->
                cards.forEach { assertNull(scene.clickLabel(it), "$it before the reveal in $language") }
                assertEquals(strings.changeCategories, scene.clickLabel(strings.allCategories), "in $language")
            }
            withScreen(PlayUiState.Revealed(QUESTION, OUTCOME), language = language) { scene, _ ->
                cards.forEach { assertEquals(strings.nextQuestion, scene.clickLabel(it), "$it in $language") }
            }
            withScreen(PlayUiState.Failed(DomainError.OUT_OF_QUESTIONS), language = language) { scene, _ ->
                assertEquals(strings.changeCategories, scene.clickLabel(strings.allCategories), "failed in $language")
            }
        }
    }

    /**
     * The categories, the points, the heart and Skip sit in one row between the two cards, in that
     * order, the points in the middle of the screen while the categories played leave them room.
     */
    @Test
    fun `the row sits between the cards with the points in the middle`() {
        val strings = stringsOf(Language.DEFAULT).playScreen
        withScreen(PlayUiState.Asking(QUESTION)) { scene, _ ->
            val cardA = scene.node(QUESTION.optionA).boundsInRoot
            val cardB = scene.node(QUESTION.optionB).boundsInRoot
            val row =
                listOf(
                    strings.allCategories,
                    POINTS_SHOWN,
                    strings.like,
                    strings.skip,
                ).map { scene.node(it).boundsInRoot }
            row.forEach { part -> assertTrue(part.center.y > cardA.bottom && part.center.y < cardB.top, "$part") }

            val (categories, points, heart, skip) = row
            assertTrue(abs(points.center.x - SHORT_PHONE_WIDTH / 2f) <= 1f, "the points are at ${points.center.x}")
            assertTrue(categories.right < points.left && heart.left > points.right && skip.left > heart.right, "$row")
            assertTrue(categories.left >= cardA.left && skip.right <= cardA.right, "the row is wider than a card: $row")
        }
    }

    /**
     * The categories played take what the like, Skip and the points leave them, so a long Cyrillic
     * name shows whole beside them, the server's names being Serbian (CLAUDE.md §8d, *The Play
     * screen*); the points move aside only as far as it needs, never over it or the heart. A
     * selection longer than the row is what is cut short, never the points, the like count or Skip.
     *
     * Measured 400 wide, as the fit test above is, since CI's Linux fonts run wider than a phone's.
     * On this Mac the row at 375 leaves *Начин живота* its 125 with room to spare.
     */
    @Test
    fun `a long Cyrillic selection shows whole beside the points and the like`() {
        val strings = stringsOf(Language.DEFAULT).playScreen
        LONG_SELECTIONS.forEach { selection ->
            withRow(selection, WIDTH - 2 * PADDING) { scene ->
                assertFalse(scene.isCutShort(selection), "\"$selection\" is cut short")
                val (categories, points, heart) =
                    listOf(selection, POINTS_SHOWN, strings.like).map { scene.node(it).boundsInRoot }
                assertTrue(categories.right < points.left && points.right < heart.left, "\"$selection\"")
            }
        }
        withRow(EVERY_CATEGORY_IN_CYRILLIC, WIDTH - 2 * PADDING) { scene ->
            assertTrue(scene.isCutShort(EVERY_CATEGORY_IN_CYRILLIC), "every category is not cut short")
            listOf(POINTS_SHOWN, LIKES.toString()).forEach { assertFalse(scene.isCutShort(it), it) }
            assertTrue(strings.skip in scene.descriptions())
        }
    }

    /**
     * The row's own rule, measured on boxes of known widths rather than text, which differs from one
     * font to another: the middle in the middle while the start leaves it room, moved right only as
     * far as the start needs, and the start cut to what is left once it needs more than the row has.
     */
    @Test
    fun `the row keeps the points in the middle until the categories need their room`() {
        // In 335, a middle 50 wide is in the middle at 142, and an end 100 wide starts at 235, 8 apart.
        mapOf(
            60 to Triple(60, 142, 235),
            134 to Triple(134, 142, 235),
            150 to Triple(150, 158, 235),
            300 to Triple(169, 177, 235),
        ).forEach { (startWidth, expected) ->
            val scene =
                ImageComposeScene(width = ROW_WIDTH, height = ROW_HEIGHT, density = Density(1f)) {
                    CentredRow(
                        gap = 8.dp,
                        start = { Probe("start", startWidth) },
                        middle = { Probe("middle", 50) },
                        end = { Probe("end", 100) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            try {
                scene.render()
                val (start, middle, end) =
                    listOf("start", "middle", "end").map { name ->
                        scene.nodes().single { name in it.descriptions }.boundsInRoot
                    }
                assertEquals(
                    expected,
                    Triple(start.width.toInt(), middle.left.toInt(), end.left.toInt()),
                    "a start $startWidth wide",
                )
                assertEquals(0f, start.left)
                assertEquals(ROW_WIDTH.toFloat(), end.right)
            } finally {
                scene.close()
            }
        }
    }

    /** A selection with nothing to serve ends in a failure, and the categories are the way out of it. */
    @Test
    fun `a failure offers Try again and the categories`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).playScreen
            withScreen(PlayUiState.Failed(DomainError.OUT_OF_QUESTIONS), language = language) { scene, actions ->
                scene.tap(strings.tryAgain)
                scene.tap(strings.allCategories)
                assertEquals(listOf("retry", "categories"), actions.tapped, "in $language")
            }
        }
    }

    /** Both percentages count up from 0 at once and reach their values at 2.5 seconds, on the scene's clock. */
    @Test
    fun `the reveal's percentages count up from 0 over two and a half seconds`() {
        withScreen(PlayUiState.Revealed(QUESTION, OUTCOME)) { scene, _ ->
            scene.renderAt(0)
            assertEquals(listOf("0%", "0%"), percentages(scene), "at the start")

            scene.renderAt(COUNTED_UP / 2)
            val halfway = percentages(scene).map { it.removeSuffix("%").toInt() }
            assertTrue(halfway[0] in 1 until 70 && halfway[1] in 1 until 30, "halfway: $halfway")

            scene.renderAt(COUNTED_UP)
            assertEquals(listOf("70%", "30%"), percentages(scene), "at two and a half seconds")
            scene.renderAt(COUNTED_UP * 2)
            assertEquals(listOf("70%", "30%"), percentages(scene), "after")
        }
    }

    /**
     * At a short phone's width, less the screen's padding, the row needs no more width than it has,
     * so nothing in it is cut short but a long selection: not the points, not the like count, not
     * how a like failed, in any language; and it is the same height whatever it shows.
     */
    @Test
    fun `nothing in the row is cut short at a short phone's width`() {
        val question = QUESTION.copy(likeCount = 1234, likedByMe = true)
        Language.entries.forEach { language ->
            val all = stringsOf(language).playScreen.allCategories
            (LIKE_FAILURES + null).forEach { error ->
                // Asked, with Skip, and answered, with its place kept.
                listOf<(() -> Unit)?>({}, null).forEach { onSkip ->
                    val at = "with $error in $language ${if (onSkip == null) "answered" else "asked"}"
                    val (width, height) =
                        sizeNeeded(ROW_WIDTH, SHORT_PHONE_HEIGHT) {
                            WyrTheme {
                                WyrStrings(language) {
                                    MiddleRow(
                                        question = question,
                                        categoriesPlayed = all,
                                        points = 12345,
                                        likeError = error,
                                        canChangeCategories = true,
                                        idle = true,
                                        onOpenCategories = {},
                                        onToggleLike = {},
                                        onSkip = onSkip,
                                    )
                                }
                            }
                        }
                    assertTrue(width <= ROW_WIDTH, "the row $at needs $width of $ROW_WIDTH")
                    assertEquals(ROW_HEIGHT, height, "the row $at")
                }
            }
        }
    }

    /**
     * Draws the scene at [nanoTime] until what that frame changed shows in its semantics. Drawn once,
     * a frame can miss what the desktop's snapshot manager, on a thread of its own, does meanwhile:
     * the count-up then starts a frame late, or its value reaches the texts a frame late, and the
     * test fails now and then. The same frame drawn again changes nothing else.
     */
    private fun ImageComposeScene.renderAt(nanoTime: Long) {
        repeat(3) {
            Snapshot.sendApplyNotifications()
            render(nanoTime)
        }
    }

    /** The percentages the scene shows, card A's first. */
    private fun percentages(scene: ImageComposeScene): List<String> = scene.texts().filter { it.endsWith("%") }

    /** The one node showing [text] or named [text]. */
    private fun ImageComposeScene.node(text: String): SemanticsNode {
        val node =
            nodes().singleOrNull { node ->
                val shown =
                    node.config
                        .getOrNull(SemanticsProperties.Text)
                        .orEmpty()
                        .map { it.text }
                text in shown || text in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }
        return assertNotNull(node, "nothing shows \"$text\"")
    }

    /** A box [width] wide, named [name] for a test to find, which a row may make narrower. */
    @Composable
    private fun Probe(
        name: String,
        width: Int,
    ) {
        Box(Modifier.width(width.dp).height(48.dp).semantics { contentDescription = name })
    }

    /** Whether the one node showing [text] cuts it short: it needs more lines than it may take. */
    private fun ImageComposeScene.isCutShort(text: String): Boolean {
        val layouts = mutableListOf<TextLayoutResult>()
        val layout = assertNotNull(node(text).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action, text)
        layout(layouts)
        // Skia's paragraphs on the desktop never report a line ellipsized, so the lines it needed.
        return layouts.single().multiParagraph.didExceedMaxLines
    }

    /**
     * [test] on the row alone, [width] wide, asked with Skip, the categories played named
     * [categoriesPlayed], [POINTS] and [LIKES] likes.
     */
    private fun withRow(
        categoriesPlayed: String,
        width: Int,
        test: (ImageComposeScene) -> Unit,
    ) {
        val scene =
            ImageComposeScene(width = width, height = ROW_HEIGHT, density = Density(1f)) {
                WyrTheme {
                    MiddleRow(
                        question = QUESTION.copy(likeCount = LIKES),
                        categoriesPlayed = categoriesPlayed,
                        points = POINTS,
                        likeError = null,
                        canChangeCategories = true,
                        idle = true,
                        onOpenCategories = {},
                        onToggleLike = {},
                        onSkip = {},
                    )
                }
            }
        try {
            scene.render()
            test(scene)
        } finally {
            scene.close()
        }
    }

    /** What a screen reader says a tap on the one node showing [text] does, if anything. */
    private fun ImageComposeScene.clickLabel(text: String): String? =
        node(text).config.getOrNull(SemanticsActions.OnClick)?.label

    private val SemanticsNode.isOff: Boolean
        get() = config.getOrNull(SemanticsProperties.Disabled) != null

    /** What the screen's callbacks were called for, in order. */
    private class Actions {
        val tapped = mutableListOf<String>()
    }

    /** [test] on [state]'s screen at the short phone's size, in the light theme, drawn at time 0. */
    private fun withScreen(
        state: PlayUiState,
        points: Int? = POINTS,
        language: Language = Language.DEFAULT,
        test: (ImageComposeScene, Actions) -> Unit,
    ) {
        val actions = Actions()
        val scene =
            ImageComposeScene(width = SHORT_PHONE_WIDTH, height = SHORT_PHONE_HEIGHT, density = Density(1f)) {
                WyrTheme(darkTheme = false) {
                    WyrStrings(language) { Screen(state, emptySet(), points, picking = null, actions) }
                }
            }
        try {
            scene.renderAt(0)
            test(scene, actions)
        } finally {
            scene.close()
        }
    }

    private fun draw(
        state: PlayUiState,
        dark: Boolean,
        width: Int,
        height: Int,
        categories: Set<Category> = emptySet(),
        picking: Set<Category>? = null,
        language: Language = Language.DEFAULT,
    ) {
        val scene =
            ImageComposeScene(width = width, height = height, density = Density(1f)) {
                WyrTheme(darkTheme = dark) { WyrStrings(language) { Screen(state, categories, POINTS, picking) } }
            }
        try {
            assertEquals(width, scene.render().width)
        } finally {
            scene.close()
        }
    }

    /** Every pixel of [state]'s screen at the short phone's size, in the light theme. */
    private fun pixels(
        state: PlayUiState,
        picking: Set<Category>?,
    ): IntArray {
        val scene =
            ImageComposeScene(width = SHORT_PHONE_WIDTH, height = SHORT_PHONE_HEIGHT, density = Density(1f)) {
                WyrTheme(darkTheme = false) { Screen(state, categories = emptySet(), POINTS, picking = picking) }
            }
        return try {
            scene
                .render()
                .toComposeImageBitmap()
                .toPixelMap()
                .buffer
        } finally {
            scene.close()
        }
    }

    /**
     * The least height [state]'s screen needs at [width] for nothing in it to be squeezed, with text
     * at [fontScale] times its size.
     */
    private fun heightNeeded(
        state: PlayUiState,
        width: Int,
        categories: Set<Category> = emptySet(),
        language: Language = Language.DEFAULT,
        fontScale: Float = 1f,
    ): Int =
        heightNeeded(width, "$state", fontScale) {
            WyrStrings(language) { Screen(state, categories, POINTS, picking = null) }
        }

    /** The least height [content] needs at [width] for nothing in it to be squeezed. */
    private fun heightNeeded(
        width: Int,
        what: String = "the content",
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ): Int {
        var needed = -1
        val scene =
            ImageComposeScene(width = width, height = SHORT_PHONE_HEIGHT, density = Density(1f, fontScale)) {
                WyrTheme {
                    Layout(content = content) { measurables, constraints ->
                        val screen = measurables.single()
                        needed = screen.minIntrinsicHeight(constraints.maxWidth)
                        val placeable = screen.measure(constraints)
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                }
            }
        try {
            scene.render()
        } finally {
            scene.close()
        }
        assertTrue(needed > 0, "$what was never measured")
        return needed
    }

    @Composable
    private fun Screen(
        state: PlayUiState,
        categories: Set<Category>,
        points: Int?,
        picking: Set<Category>?,
        actions: Actions = Actions(),
    ) {
        PlayScreen(
            state = state,
            categories = categories,
            points = points,
            picking = picking,
            onChoose = { side -> actions.tapped += "choose $side" },
            onSkip = { actions.tapped += "skip" },
            onNext = { actions.tapped += "next" },
            onToggleLike = { actions.tapped += "like" },
            onRetry = { actions.tapped += "retry" },
            onOpenCategories = { actions.tapped += "categories" },
            onToggleCategory = {},
            onSelectAllCategories = {},
            onApplyCategories = {},
            onCloseCategories = {},
        )
    }

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 900

        /** An iPhone SE (667 high) less its status bar (20) and the top bar above the Play screen (48). */
        const val SHORT_PHONE_WIDTH = 375
        const val SHORT_PHONE_HEIGHT = 599

        /** The screen's padding on each side (`WyrDimens.screenPadding`). */
        const val PADDING = 20

        /** The short phone's width less the screen's padding on each side. */
        const val ROW_WIDTH = SHORT_PHONE_WIDTH - 2 * PADDING

        /** The heart button's touch target and the row's padding above and below it. */
        const val ROW_HEIGHT = 56

        /** The scene's clock, in nanoseconds, once the reveal has counted up. */
        const val COUNTED_UP = COUNT_UP_MILLIS * 1_000_000L

        const val POINTS = 42

        /** [POINTS] as the default language shows them, which the scenes are drawn in unless told. */
        val POINTS_SHOWN = stringsOf(Language.DEFAULT).points(POINTS)

        /** A like count of a question many like. */
        const val LIKES = 12

        /**
         * The longest of the server's category names (`feat/server-categories`' V6), the two words
         * of *Lifestyle*; another single one; and two short ones together.
         */
        val LONG_SELECTIONS = listOf("Начин живота", "Супермоћи", "Храна, Етика")

        /** Every one of the server's categories played, longer than the row. */
        const val EVERY_CATEGORY_IN_CYRILLIC = "Храна, Начин живота, Етика, Супермоћи, Апсурдно"

        /** Two lines an option on a phone, as most seeds are. */
        val QUESTION =
            Question(
                id = "q1",
                optionA = "Be able to fly but only a metre off the ground",
                optionB = "Turn invisible but only while nobody is looking",
                categories = setOf(Category.SUPERPOWERS),
            )
        val ONE_LINE_QUESTION = QUESTION.copy(optionA = "Fly", optionB = "Swim")

        /** None, which is every category; one; and every one, the longest line the row can hold. */
        val SELECTIONS: List<Set<Category>> =
            listOf(emptySet(), setOf(Category.ETHICS), Category.selectable.toSet())

        /** The phone's font size as it is, Android's largest before Android 14, and twice it, the largest since. */
        val FONT_SCALES: List<Float> = listOf(1f, 1.3f, 2f)

        /** Every way a like's failure is worded, the longest among them. */
        val LIKE_FAILURES: List<DomainError?> =
            listOf(DomainError.NETWORK, DomainError.RATE_LIMITED, DomainError.QUESTION_NOT_FOUND, DomainError.SERVER)

        val OUTCOME =
            VoteOutcome(
                yourSide = Side.B,
                tally = Tally(votesA = 7, votesB = 3),
                pointsAwarded = 1,
                totalPoints = POINTS,
            )

        /** Every state the screen can be in, on [question] where there is one. */
        fun statesOf(question: Question): List<PlayUiState> =
            listOf(
                PlayUiState.Loading,
                PlayUiState.Failed(DomainError.NETWORK),
                PlayUiState.Failed(DomainError.OUT_OF_QUESTIONS),
                PlayUiState.Failed(DomainError.RATE_LIMITED),
                PlayUiState.Failed(DomainError.QUESTION_NOT_FOUND),
                PlayUiState.Failed(DomainError.SERVER),
                PlayUiState.Asking(question),
                PlayUiState.Asking(question, isSubmitting = true),
                PlayUiState.Asking(question.copy(likeCount = 1, likedByMe = true), isLiking = true),
                PlayUiState.Asking(question.copy(likeCount = 12), likeError = DomainError.NETWORK),
                PlayUiState.Revealed(question, OUTCOME),
                PlayUiState.Revealed(question, OUTCOME.copy(pointsAwarded = 0, replayed = true)),
                PlayUiState.Revealed(question.copy(likeCount = 3), OUTCOME, isLiking = true),
                PlayUiState.Revealed(question.copy(likeCount = 1234, likedByMe = true), OUTCOME),
                PlayUiState.Revealed(question, OUTCOME, likeError = DomainError.NETWORK),
                PlayUiState.Revealed(question, OUTCOME, likeError = DomainError.QUESTION_NOT_FOUND),
            )

        /**
         * What some states show in [strings], with [points] as the points, each its texts in any order,
         * and then the names it gives a screen reader for what has no text, from the top down.
         */
        fun expectedOf(
            strings: PlayStrings,
            points: String,
        ): List<Pair<PlayUiState, Pair<List<String>, List<String>>>> {
            val all = strings.allCategories
            val a = QUESTION.optionA
            val b = QUESTION.optionB
            val revealedA = strings.percent(70)
            val revealedB = strings.percent(30)
            val like = listOf(strings.like)
            val likeAndSkip = listOf(strings.like, strings.skip)
            return listOf(
                PlayUiState.Loading to (emptyList<String>() to listOf(strings.loading)),
                PlayUiState.Failed(DomainError.NETWORK) to
                    (listOf(strings.cannotReach, strings.tryAgain, all) to emptyList()),
                PlayUiState.Failed(DomainError.OUT_OF_QUESTIONS) to
                    (listOf(strings.outOfQuestions, strings.tryAgain, all) to emptyList()),
                PlayUiState.Failed(DomainError.SERVER) to
                    (listOf(strings.somethingWrong, strings.tryAgain, all) to emptyList()),
                PlayUiState.Asking(QUESTION) to (listOf(a, all, points, "0", b) to likeAndSkip),
                PlayUiState.Asking(QUESTION.copy(likeCount = 12), likeError = DomainError.NETWORK) to
                    (listOf(a, all, strings.cannotReach, "12", b) to likeAndSkip),
                PlayUiState.Revealed(QUESTION, OUTCOME) to
                    (listOf(a, revealedA, all, points, "0", b, revealedB) to like),
                PlayUiState.Revealed(QUESTION, OUTCOME.copy(pointsAwarded = 0, replayed = true)) to
                    (listOf(a, revealedA, all, points, "0", b, revealedB) to like),
                PlayUiState.Revealed(
                    QUESTION.copy(likeCount = 3),
                    OUTCOME,
                    likeError = DomainError.QUESTION_NOT_FOUND,
                ) to
                    (listOf(a, revealedA, all, strings.questionGone, "3", b, revealedB) to like),
            )
        }
    }
}
