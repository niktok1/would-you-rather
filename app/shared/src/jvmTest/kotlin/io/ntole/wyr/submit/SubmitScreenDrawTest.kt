package io.ntole.wyr.submit

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.SubmissionRules
import io.ntole.wyr.everyText
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.WyrStrings
import io.ntole.wyr.language.stringsOf
import io.ntole.wyr.nodes
import io.ntole.wyr.sizeNeeded
import io.ntole.wyr.tap
import io.ntole.wyr.texts
import io.ntole.wyr.theme.WyrTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The Submit screen's form drawn off screen at two phones' sizes, in each theme and each language,
 * from every state it can be in, and read and tapped through its semantics. Compose measures and
 * draws it all, so a layout that cannot be measured fails here rather than when the form opens. The
 * form scrolls, so on a short phone what does not fit is scrolled to rather than squeezed.
 */
class SubmitScreenDrawTest {
    @Test
    fun `the screen draws in every state it can be in`() {
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
     * A question written and nothing wrong with it fits an iPhone SE whole in every language, Send
     * with its cost among it; a failure, the note or the rules' words under a long option may take it
     * past, and the form scrolls. Measured 400 wide, since CI's Linux fonts wrap wider than a phone's.
     */
    @Test
    fun `a written question fits a short phone whole in every language`() {
        Language.entries.forEach { language ->
            val (_, height) =
                sizeNeeded(WIDTH, SHORT_PHONE_HEIGHT) {
                    WyrTheme { WyrStrings(language) { SubmitScreen(state = WRITTEN, actions = Recorder()) } }
                }
            assertTrue(height <= SHORT_PHONE_HEIGHT, "$language needs $height of $SHORT_PHONE_HEIGHT")
        }
    }

    /** Send names its cost in every language, and sends when the points pay for it. */
    @Test
    fun `Send names its cost and sends what is written`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).accountScreens
            val actions = Recorder()
            val scene = scene(WRITTEN, language, actions)
            try {
                assertFalse(sendButton(scene, sendText(stringsOf(language))).isOff, "$language")
                assertFalse(strings.notEnoughPoints in scene.texts(), "$language")

                scene.tap(sendText(stringsOf(language)))

                assertEquals(listOf("submit"), actions.calls, "$language")
            } finally {
                scene.close()
            }
        }
    }

    /** Fewer points than a question costs: Send is off, and one short line says why. */
    @Test
    fun `Send is off with a note while the points are too few`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).accountScreens
            val scene = scene(WRITTEN.copy(points = SubmissionRules.COST - 1), language)
            try {
                assertTrue(sendButton(scene, sendText(stringsOf(language))).isOff, "$language")
                assertTrue(strings.notEnoughPoints in scene.texts(), "$language: ${scene.texts()}")
            } finally {
                scene.close()
            }
        }
    }

    /** Points not read yet say nothing, and Send waits for them. */
    @Test
    fun `Send waits for the points without a note`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).accountScreens
            val scene = scene(WRITTEN.copy(points = null), language)
            try {
                assertTrue(sendButton(scene, sendText(stringsOf(language))).isOff, "$language")
                assertFalse(strings.notEnoughPoints in scene.texts(), "$language")
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun `points that cannot be read offer to try again`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).accountScreens
            val actions = Recorder()
            val scene = scene(SubmitState(pointsFailure = SubmitFailure(DomainError.NETWORK)), language, actions)
            try {
                assertTrue(strings.offline in scene.everyText(), "$language")
                scene.tap(stringsOf(language).tryAgain)
                assertEquals(listOf("refresh"), actions.calls, "$language")
            } finally {
                scene.close()
            }
        }
    }

    /** The one Send and what it shows, as a screen reader finds it. */
    private fun sendButton(
        scene: ImageComposeScene,
        text: String,
    ) = assertNotNull(scene.nodes().singleOrNull { text in it.texts }, "no \"$text\" in ${scene.texts()}")

    private val SemanticsNode.isOff: Boolean
        get() = SemanticsProperties.Disabled in config

    private fun scene(
        state: SubmitState,
        language: Language,
        actions: SubmitActions = Recorder(),
        dark: Boolean = false,
        width: Int = WIDTH,
        height: Int = HEIGHT,
    ): ImageComposeScene =
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            WyrTheme(darkTheme = dark) { WyrStrings(language) { SubmitScreen(state = state, actions = actions) } }
        }.also { it.render() }

    /** What the form asked for, in order. */
    private class Recorder : SubmitActions {
        val calls = mutableListOf<String>()

        override fun refresh() {
            calls += "refresh"
        }

        override fun leftForm() {
            calls += "left"
        }

        override fun setOptionA(text: String) = Unit

        override fun setOptionB(text: String) = Unit

        override fun toggleCategory(category: Category) = Unit

        override fun submit() {
            calls += "submit"
        }
    }

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 900

        /** An iPhone SE (667 high) less its status bar (20) and the top bar above the screen (48). */
        const val SHORT_PHONE_WIDTH = 375
        const val SHORT_PHONE_HEIGHT = 599

        val LONGEST = "Be able to fly ".repeat(20).take(SubmissionRules.MAX_OPTION_LENGTH)

        val WRITTEN =
            SubmitState(
                optionA = "Fly",
                optionB = "Swim",
                categories = setOf(Category.SUPERPOWERS, Category.RANDOM),
                points = 12,
            )

        val STATES =
            listOf(
                SubmitState(),
                SubmitState(running = SubmitAction.LOAD),
                SubmitState(pointsFailure = SubmitFailure(DomainError.NETWORK)),
                SubmitState(points = 0),
                // Offline from the start: a refused question over points never read.
                SubmitState(
                    optionA = "Fly",
                    optionB = "Swim",
                    categories = setOf(Category.FOOD),
                    submitFailure = SubmitFailure(DomainError.NETWORK),
                    pointsFailure = SubmitFailure(DomainError.NETWORK),
                ),
                WRITTEN,
                WRITTEN.copy(points = 0),
                WRITTEN.copy(running = SubmitAction.LOAD),
                WRITTEN.copy(optionA = "   ", optionB = "x".repeat(SubmissionRules.MAX_OPTION_LENGTH + 1)),
                WRITTEN.copy(optionA = "Fly\nhigh", optionB = LONGEST, categories = emptySet()),
                WRITTEN.copy(optionB = "FLY"),
                WRITTEN.copy(running = SubmitAction.SUBMIT),
                WRITTEN.copy(submitFailure = SubmitFailure(DomainError.SUBMISSION_LIMIT)),
                WRITTEN.copy(submitFailure = SubmitFailure(DomainError.RATE_LIMITED, 42.seconds)),
                WRITTEN.copy(
                    submitFailure = SubmitFailure(DomainError.SUBMISSION_LIMIT),
                    pointsFailure = SubmitFailure(DomainError.NETWORK),
                    points = 0,
                ),
            )
    }
}
