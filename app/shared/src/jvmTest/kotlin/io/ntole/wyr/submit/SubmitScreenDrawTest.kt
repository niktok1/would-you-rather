package io.ntole.wyr.submit

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionRules
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.theme.WyrTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The Submit screen drawn off screen at two phones' sizes, in each theme, from every state it can be
 * in. Compose measures and draws it all, so a layout that cannot be measured fails here rather than
 * when the tab opens. The screen scrolls, the form with the list under it, so on a short phone what
 * does not fit is scrolled to rather than squeezed.
 */
class SubmitScreenDrawTest {
    @Test
    fun `the screen draws in every state it can be in`() {
        STATES.forEach { state ->
            listOf(false, true).forEach { dark ->
                draw(state, dark, WIDTH, HEIGHT)
                draw(state, dark, SHORT_PHONE_WIDTH, SHORT_PHONE_HEIGHT)
            }
        }
    }

    private fun draw(
        state: SubmitState,
        dark: Boolean,
        width: Int,
        height: Int,
    ) {
        val scene =
            ImageComposeScene(width = width, height = height, density = Density(1f)) {
                WyrTheme(darkTheme = dark) { SubmitScreen(state = state, actions = NoActions) }
            }
        try {
            assertEquals(width, scene.render().width)
        } finally {
            scene.close()
        }
    }

    private object NoActions : SubmitActions {
        override fun refresh() = Unit

        override fun setOptionA(text: String) = Unit

        override fun setOptionB(text: String) = Unit

        override fun toggleCategory(category: Category) = Unit

        override fun submit() = Unit
    }

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 900

        /** An iPhone SE (667 high) less its status bar (20) and the top bar above the screen (48). */
        const val SHORT_PHONE_WIDTH = 375
        const val SHORT_PHONE_HEIGHT = 599

        val LONGEST = "Be able to fly ".repeat(20).take(SubmissionRules.MAX_OPTION_LENGTH)

        val PENDING =
            Submission(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = setOf(Category.SUPERPOWERS),
                status = SubmissionStatus.PENDING,
                rejectionReason = null,
                submittedAt = Instant.parse("2026-09-25T12:00:00Z"),
            )

        /** One of every status, the longest options and reason among them. */
        val EVERY_STATUS =
            listOf(
                PENDING,
                PENDING.copy(id = "q2", status = SubmissionStatus.APPROVED, categories = Category.entries.toSet()),
                PENDING.copy(
                    id = "q3",
                    optionA = LONGEST,
                    optionB = LONGEST.reversed(),
                    status = SubmissionStatus.REJECTED,
                    rejectionReason = "x".repeat(200),
                ),
                PENDING.copy(id = "q4", status = SubmissionStatus.REJECTED),
                PENDING.copy(id = "q5", status = SubmissionStatus.RETIRED),
                PENDING.copy(id = "q6", status = SubmissionStatus.OTHER),
            )

        val WRITTEN =
            SubmitState(
                optionA = "Fly",
                optionB = "Swim",
                categories = setOf(Category.SUPERPOWERS, Category.RANDOM),
                submissions = EVERY_STATUS,
            )

        val STATES =
            listOf(
                SubmitState(),
                SubmitState(running = SubmitAction.LOAD),
                SubmitState(listFailure = SubmitFailure(DomainError.NETWORK)),
                // Offline from the start: a refused question over a list never read.
                SubmitState(
                    optionA = "Fly",
                    optionB = "Swim",
                    categories = setOf(Category.FOOD),
                    submitFailure = SubmitFailure(DomainError.NETWORK),
                    listFailure = SubmitFailure(DomainError.NETWORK),
                ),
                SubmitState(submissions = emptyList()),
                WRITTEN,
                WRITTEN.copy(running = SubmitAction.LOAD),
                WRITTEN.copy(optionA = "   ", optionB = "x".repeat(SubmissionRules.MAX_OPTION_LENGTH + 1)),
                WRITTEN.copy(optionA = "Fly\nhigh", optionB = LONGEST, categories = emptySet()),
                WRITTEN.copy(optionB = "FLY"),
                WRITTEN.copy(running = SubmitAction.SUBMIT),
                WRITTEN.copy(submitFailure = SubmitFailure(DomainError.SUBMISSION_LIMIT)),
                WRITTEN.copy(submitFailure = SubmitFailure(DomainError.RATE_LIMITED, 42.seconds)),
                WRITTEN.copy(
                    submitFailure = SubmitFailure(DomainError.SUBMISSION_LIMIT),
                    listFailure = SubmitFailure(DomainError.NETWORK),
                ),
                SubmitState(
                    submissions = EVERY_STATUS,
                    sent = true,
                    listFailure = SubmitFailure(DomainError.SERVER),
                ),
            )
    }
}
