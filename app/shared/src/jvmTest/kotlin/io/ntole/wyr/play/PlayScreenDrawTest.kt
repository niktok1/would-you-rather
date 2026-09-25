package io.ntole.wyr.play

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Play screen drawn off screen at two phones' sizes, in each theme, from every state it can be
 * in. Compose measures and draws it all, so a layout that cannot be measured fails here rather than
 * when the tab opens. Whether what it draws fits is asked separately, since a squeezed card draws.
 */
class PlayScreenDrawTest {
    @Test
    fun `the screen draws in every state it can be in`() {
        statesOf(QUESTION).forEach { state ->
            listOf(false, true).forEach { dark ->
                draw(state, dark, WIDTH, HEIGHT)
                draw(state, dark, SHORT_PHONE_WIDTH, SHORT_PHONE_HEIGHT)
            }
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
            val needed = heightNeeded(state, WIDTH)
            assertTrue(needed <= SHORT_PHONE_HEIGHT, "$state needs $needed of $SHORT_PHONE_HEIGHT")
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
    ) {
        val scene =
            ImageComposeScene(width = width, height = height, density = Density(1f)) {
                WyrTheme(darkTheme = dark) { Screen(state) }
            }
        try {
            assertEquals(width, scene.render().width)
        } finally {
            scene.close()
        }
    }

    /** The least height [state]'s screen needs at [width] for nothing in it to be squeezed. */
    private fun heightNeeded(
        state: PlayUiState,
        width: Int,
    ): Int {
        var needed = -1
        val scene =
            ImageComposeScene(width = width, height = SHORT_PHONE_HEIGHT, density = Density(1f)) {
                WyrTheme {
                    Layout(content = { Screen(state) }) { measurables, constraints ->
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
        assertTrue(needed > 0, "$state was never measured")
        return needed
    }

    @Composable
    private fun Screen(state: PlayUiState) {
        PlayScreen(state = state, onChoose = {}, onSkip = {}, onToggleLike = {}, onNext = {}, onRetry = {})
    }

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 900

        /**
         * An iPhone SE (667 high) less its status bar (20) and the tab row above the Play tab (48).
         * Android's 360x640 class leaves about 520, which cuts the reveal's percentages off: a limit
         * of the cards' layout, not of the controls under them.
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
        val OUTCOME =
            VoteOutcome(
                questionId = QUESTION.id,
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
