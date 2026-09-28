package io.ntole.wyr.share

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.language.GOOGLE_PLAY
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.WyrStrings
import io.ntole.wyr.language.fill
import io.ntole.wyr.language.stringsOf
import io.ntole.wyr.settle
import io.ntole.wyr.tap
import io.ntole.wyr.texts
import io.ntole.wyr.theme.WyrDarkColors
import io.ntole.wyr.theme.WyrLightColors
import io.ntole.wyr.theme.WyrTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Sharing a question (CLAUDE.md §8d, *Sharing*): the dialog shows the image as it will go, a switch for
 * the results while there are any, and Share hands a 1080 by 1350 image and the message with the store
 * page to the platform's share sheet, a recording one here; and what the dialog does with each outcome.
 */
class ShareDialogTest {
    @Test
    fun `Share hands the image and the message to the share sheet`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).playScreen.share
            val sheet = RecordingSheet(ShareOutcome.OPENED)
            withDialog(ANSWERED, sheet, language) { scene, dialog ->
                scene.tap(strings.share)

                val (image, text) = sheet.shared.single()
                assertEquals(SHARE_IMAGE_WIDTH_PX to 1350, image.width to image.height, "in $language")
                assertEquals(strings.message.fill(STORE_URL), text)
                assertTrue(STORE_URL in text, "the message links to the store: $text")
                assertEquals(listOf(true to ShareOutcome.OPENED), dialog.shared, "in $language")
                assertEquals(1, dialog.dismissed, "a share sheet that opened closes the dialog")
            }
        }
    }

    /** The image is the question, in the language shown, with its results only while the switch is on. */
    @Test
    fun `the results show only while the switch is on`() {
        Language.entries.forEach { language ->
            val shown = stringsOf(language)
            val strings = shown.playScreen.share
            withDialog(ANSWERED, RecordingSheet(ShareOutcome.OPENED), language) { scene, _ ->
                val texts = scene.texts()
                listOf(shown.gameName, OPTION_A, OPTION_B, strings.invite.fill(GOOGLE_PLAY)).forEach {
                    assertTrue(it in texts, "\"$it\" in $language")
                }
                listOf("75%", "25%", strings.myPick).forEach { assertTrue(it in texts, "\"$it\" in $language") }

                scene.tap(strings.showResults)

                val without = scene.texts()
                listOf("75%", "25%", strings.myPick).forEach { assertFalse(it in without, "\"$it\" in $language") }
                assertTrue(OPTION_A in without && OPTION_B in without)
            }
        }
    }

    /** A question not answered yet has no results to show, so no switch either, and the image shows none. */
    @Test
    fun `a question not answered has no switch`() {
        val strings = stringsOf(Language.DEFAULT).playScreen.share
        val sheet = RecordingSheet(ShareOutcome.OPENED)
        withDialog(ASKED, sheet) { scene, dialog ->
            val texts = scene.texts()
            assertFalse(strings.showResults in texts)
            assertFalse(strings.myPick in texts)
            assertFalse(texts.any { it.endsWith("%") }, "$texts")

            scene.tap(strings.share)

            assertEquals(listOf(false to ShareOutcome.OPENED), dialog.shared)
        }
    }

    /** Switched off, the results are no part of what was shared, for the analytics. */
    @Test
    fun `a share without the results says so`() {
        val strings = stringsOf(Language.DEFAULT).playScreen.share
        withDialog(ANSWERED, RecordingSheet(ShareOutcome.OPENED)) { scene, dialog ->
            scene.tap(strings.showResults)
            scene.tap(strings.share)

            assertEquals(listOf(false to ShareOutcome.OPENED), dialog.shared)
        }
    }

    /** A copy or a download says so and stays, Cancel then Close; a failure says it failed and stays. */
    @Test
    fun `a copy, a download and a failure each say so`() {
        val shown = stringsOf(Language.DEFAULT)
        val strings = shown.playScreen.share
        mapOf(
            ShareOutcome.COPIED to (strings.copied to strings.close),
            ShareOutcome.SAVED to (strings.saved to strings.close),
            ShareOutcome.FAILED to (strings.failed to shown.cancel),
        ).forEach { (outcome, expected) ->
            val (line, leave) = expected
            withDialog(ANSWERED, RecordingSheet(outcome)) { scene, dialog ->
                scene.tap(strings.share)

                assertTrue(line in scene.texts(), "$outcome")
                assertTrue(leave in scene.texts(), "$outcome")
                assertEquals(0, dialog.dismissed, "$outcome")
                assertEquals(listOf(true to outcome), dialog.shared)

                scene.tap(leave)
                assertEquals(1, dialog.dismissed, "$outcome")
            }
        }
    }

    /** The image is drawn: card A's pink and card B's amber at its middle's left, the page around them. */
    @Test
    fun `the image is the cards in the theme's colours`() {
        listOf(WyrLightColors, WyrDarkColors).forEach { colors ->
            val sheet = RecordingSheet(ShareOutcome.OPENED)
            withDialog(ANSWERED, sheet, dark = colors.isDark) { scene, _ ->
                scene.tap(stringsOf(Language.DEFAULT).playScreen.share.share)

                val pixels =
                    sheet.shared
                        .single()
                        .first
                        .toPixelMap()
                // Inside each card, near its left edge, clear of its text; and the page's corner.
                assertEquals(colors.optionA.toArgb(), pixels[CARD_EDGE_X, CARD_A_Y].toArgb(), "card A")
                assertEquals(colors.optionB.toArgb(), pixels[CARD_EDGE_X, CARD_B_Y].toArgb(), "card B")
                assertEquals(colors.pageBackground.toArgb(), pixels[1, 1].toArgb(), "the page")
                assertNotEquals(pixels[CARD_EDGE_X, CARD_A_Y], pixels[CARD_EDGE_X, CARD_B_Y])
            }
        }
    }

    private fun withDialog(
        question: SharedQuestion,
        sheet: ShareSheet,
        language: Language = Language.DEFAULT,
        dark: Boolean = false,
        test: (ImageComposeScene, DialogCalls) -> Unit,
    ) {
        val calls = DialogCalls()
        val scene =
            ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
                CompositionLocalProvider(LocalShareSheet provides sheet) {
                    WyrTheme(darkTheme = dark) {
                        WyrStrings(language) {
                            ShareDialog(
                                question = question,
                                onDismiss = { calls.dismissed++ },
                                onShared = { withResults, outcome -> calls.shared += withResults to outcome },
                            )
                        }
                    }
                }
            }
        try {
            scene.settle()
            test(scene, calls)
        } finally {
            scene.close()
        }
    }

    /** What the dialog told its caller. */
    private class DialogCalls {
        var dismissed = 0
        val shared = mutableListOf<Pair<Boolean, ShareOutcome>>()
    }

    /** A share sheet that keeps what it was handed and answers [outcome]. */
    private class RecordingSheet(
        private val outcome: ShareOutcome,
    ) : ShareSheet {
        val shared = mutableListOf<Pair<ImageBitmap, String>>()

        override suspend fun share(
            image: ImageBitmap,
            text: String,
        ): ShareOutcome {
            shared += image to text
            return outcome
        }
    }

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 900

        const val OPTION_A = "Fly"
        const val OPTION_B = "Turn invisible"

        val ASKED = SharedQuestion(id = "q1", categories = setOf("FOOD"), optionA = OPTION_A, optionB = OPTION_B)
        val ANSWERED = ASKED.copy(tally = Tally(votesA = 3, votesB = 1), pick = Side.A)

        /** On the 1080 by 1350 image, 3 pixels a dp: 24 of padding, then the cards, 28 in. */
        const val CARD_EDGE_X = 84

        /** Down the middle of each card's height, well clear of the title and of each other. */
        const val CARD_A_Y = 450
        const val CARD_B_Y = 900
    }
}
