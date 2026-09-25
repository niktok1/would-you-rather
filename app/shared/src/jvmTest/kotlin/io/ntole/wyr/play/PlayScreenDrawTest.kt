package io.ntole.wyr.play

import androidx.compose.ui.ImageComposeScene
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

/**
 * The Play screen drawn off screen at a phone's size, in each theme, from every state it can be in.
 * Compose measures and draws it all, so a layout that cannot be measured fails here rather than
 * when the tab opens.
 */
class PlayScreenDrawTest {
    @Test
    fun `the screen draws in every state it can be in`() {
        val question =
            Question(
                id = "q1",
                optionA = "Be able to fly but only a metre off the ground",
                optionB = "Turn invisible but only while nobody is looking",
                categories = setOf(Category.SUPERPOWERS),
            )
        val outcome =
            VoteOutcome(
                questionId = question.id,
                yourSide = Side.B,
                tally = Tally(votesA = 7, votesB = 3),
                pointsAwarded = 1,
                totalPoints = 42,
            )
        val states =
            listOf(
                PlayUiState.Loading,
                PlayUiState.Failed(DomainError.NETWORK),
                PlayUiState.Failed(DomainError.OUT_OF_QUESTIONS),
                PlayUiState.Asking(question),
                PlayUiState.Asking(question, isSubmitting = true),
                PlayUiState.Asking(question.copy(likeCount = 1, likedByMe = true), isLiking = true),
                PlayUiState.Asking(question.copy(likeCount = 12), likeError = DomainError.NETWORK),
                PlayUiState.Revealed(question, outcome),
                PlayUiState.Revealed(question, outcome.copy(pointsAwarded = 0, replayed = true)),
                PlayUiState.Revealed(question.copy(likeCount = 3), outcome, isLiking = true),
                PlayUiState.Revealed(question, outcome, likeError = DomainError.QUESTION_NOT_FOUND),
            )

        states.forEach { state -> listOf(false, true).forEach { dark -> draw(state, dark) } }
    }

    private fun draw(
        state: PlayUiState,
        dark: Boolean,
    ) {
        val scene =
            ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
                WyrTheme(darkTheme = dark) {
                    PlayScreen(state = state, onChoose = {}, onSkip = {}, onToggleLike = {}, onNext = {}, onRetry = {})
                }
            }
        try {
            assertEquals(WIDTH, scene.render().width)
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 900
    }
}
