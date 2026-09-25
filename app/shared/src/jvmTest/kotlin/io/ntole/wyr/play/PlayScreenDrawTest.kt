package io.ntole.wyr.play

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Density
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.core.domain.vote.VoteOutcome
import io.ntole.wyr.theme.WyrTheme
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Play screen drawn off screen at two phones' sizes, in each theme, from every state it can be
 * in, with every category played or a few, and with the category picker open. Compose measures and
 * draws it all, so a layout that cannot be measured fails here rather than when the tab opens.
 * Whether what it draws fits is asked separately, since a squeezed card draws, and so is whether the
 * picker is drawn at all, since the screen draws without it too.
 */
class PlayScreenDrawTest {
    @Test
    fun `the screen draws in every state it can be in`() {
        statesOf(QUESTION).forEach { state ->
            listOf(false, true).forEach { dark ->
                SELECTIONS.forEach { categories ->
                    draw(state, dark, WIDTH, HEIGHT, categories)
                    draw(state, dark, SHORT_PHONE_WIDTH, SHORT_PHONE_HEIGHT, categories)
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
     * cut off what they hold: the reveal's vote counts first, then its percentages. Drawing cannot
     * see that, so this asks the screen how much height it needs at an iPhone SE's height.
     *
     * The height a line takes differs little from one font to another, but where text wraps differs
     * a lot, and CI's Linux has wider fonts than a phone (Noto Sans or DejaVu Sans). So it is measured
     * at the width drawn above, which leaves the screen's own copy, the verdict above all, 25 more
     * than 375 does to stay on one line, and the options are one short line each, so the cards need
     * only their least height (`optionMinHeight`) and what is measured is everything around them.
     * On this Mac the reveal of [QUESTION] at 375 wide needs 593, and of [ONE_LINE_QUESTION] 569.
     */
    @Test
    fun `every state fits a short phone without squeezing the option cards`() {
        statesOf(ONE_LINE_QUESTION).forEach { state ->
            SELECTIONS.forEach { categories ->
                val needed = heightNeeded(state, WIDTH, categories)
                assertTrue(needed <= SHORT_PHONE_HEIGHT, "$state on $categories needs $needed of $SHORT_PHONE_HEIGHT")
            }
        }
    }

    /**
     * The categories played share the header's one row with the reveal's points, so however many are
     * selected they take one line of it, cut short at the narrow phone's width rather than wrapped.
     */
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

    /** A failed like says so in the verdict's place, so the reveal is no taller for it. */
    @Test
    fun `a failed like takes no height from the reveal`() {
        val revealed = PlayUiState.Revealed(QUESTION, OUTCOME)
        listOf(DomainError.NETWORK, DomainError.QUESTION_NOT_FOUND).forEach { error ->
            assertEquals(
                heightNeeded(revealed, WIDTH),
                heightNeeded(revealed.copy(likeError = error), WIDTH),
                "with $error",
            )
        }
    }

    private fun draw(
        state: PlayUiState,
        dark: Boolean,
        width: Int,
        height: Int,
        categories: Set<Category> = emptySet(),
        picking: Set<Category>? = null,
    ) {
        val scene =
            ImageComposeScene(width = width, height = height, density = Density(1f)) {
                WyrTheme(darkTheme = dark) { Screen(state, categories, picking) }
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
                WyrTheme(darkTheme = false) { Screen(state, categories = emptySet(), picking = picking) }
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

    /** The least height [state]'s screen needs at [width] for nothing in it to be squeezed. */
    private fun heightNeeded(
        state: PlayUiState,
        width: Int,
        categories: Set<Category> = emptySet(),
    ): Int = heightNeeded(width, "$state") { Screen(state, categories, picking = null) }

    /** The least height [content] needs at [width] for nothing in it to be squeezed. */
    private fun heightNeeded(
        width: Int,
        what: String = "the content",
        content: @Composable () -> Unit,
    ): Int {
        var needed = -1
        val scene =
            ImageComposeScene(width = width, height = SHORT_PHONE_HEIGHT, density = Density(1f)) {
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
        picking: Set<Category>?,
    ) {
        PlayScreen(
            state = state,
            categories = categories,
            picking = picking,
            onChoose = {},
            onSkip = {},
            onToggleLike = {},
            onNext = {},
            onRetry = {},
            onOpenCategories = {},
            onToggleCategory = {},
            onSelectAllCategories = {},
            onApplyCategories = {},
            onCloseCategories = {},
        )
    }

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 900

        /**
         * An iPhone SE (667 high) less its status bar (20) and the top bar above the Play screen (48).
         * Android's 360x640 class leaves about 520, which cuts the reveal's percentages off: a limit
         * of the cards' layout, not of the controls under them. Since the categories row is drawn in
         * every state, a question not answered yet (537 here) no longer fits there either, and its
         * cards are squeezed below their least height (CLAUDE.md §8b, provisional).
         */
        const val SHORT_PHONE_WIDTH = 375
        const val SHORT_PHONE_HEIGHT = 599

        /** Two lines an option on a phone, as most seeds are. */
        val QUESTION =
            Question(
                id = "q1",
                optionA = "Be able to fly but only a metre off the ground",
                optionB = "Turn invisible but only while nobody is looking",
                categories = setOf(Category.SUPERPOWERS),
            )
        val ONE_LINE_QUESTION = QUESTION.copy(optionA = "Fly", optionB = "Swim")

        /** None, which is every category; one; and every one, the longest line the header can hold. */
        val SELECTIONS: List<Set<Category>> =
            listOf(emptySet(), setOf(Category.ETHICS), Category.selectable.toSet())
        val OUTCOME =
            VoteOutcome(
                yourSide = Side.B,
                tally = Tally(votesA = 7, votesB = 3),
                pointsAwarded = 1,
                totalPoints = 42,
            )

        /** Every state the screen can be in, on [question] where there is one. */
        fun statesOf(question: Question): List<PlayUiState> =
            listOf(
                PlayUiState.Loading,
                PlayUiState.Failed(DomainError.NETWORK),
                PlayUiState.Failed(DomainError.OUT_OF_QUESTIONS),
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
    }
}
