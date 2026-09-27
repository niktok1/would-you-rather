package io.ntole.wyr.play

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import io.ntole.wyr.CountedBy
import io.ntole.wyr.Recompositions
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.question.Question
import io.ntole.wyr.core.domain.reaction.Reaction
import io.ntole.wyr.core.domain.submission.SubmissionRules
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.Tally
import io.ntole.wyr.core.domain.vote.VoteOutcome
import io.ntole.wyr.descriptions
import io.ntole.wyr.everyNode
import io.ntole.wyr.everyText
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.Strings
import io.ntole.wyr.language.WyrStrings
import io.ntole.wyr.language.fill
import io.ntole.wyr.language.optionText
import io.ntole.wyr.language.stringsOf
import io.ntole.wyr.nodes
import io.ntole.wyr.renderAt
import io.ntole.wyr.sizeNeeded
import io.ntole.wyr.tap
import io.ntole.wyr.texts
import io.ntole.wyr.theme.WyrColors
import io.ntole.wyr.theme.WyrDarkColors
import io.ntole.wyr.theme.WyrLightColors
import io.ntole.wyr.theme.WyrTheme
import io.ntole.wyr.theme.WyrTypeScale
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Play screen (CLAUDE.md §8d, *The Play screen*) drawn off screen at two phones' sizes, and at two
 * phones on their side and a desktop window's (§8d, *Wide screens*), in each theme and each language,
 * from every state it can be in; read through its semantics, as a screen reader reads it, and tapped
 * through them. The categories played are on the top bar above it (`TopBarsDrawTest`). Compose measures and
 * draws it all, so a layout that cannot be measured fails here rather than when the screen opens.
 * Whether what it draws fits is asked separately, since a squeezed card draws.
 */
class PlayScreenDrawTest {
    @Test
    fun `the screen draws in every state in both themes and every language`() {
        statesOf(QUESTION).forEach { state ->
            listOf(false, true).forEach { dark ->
                Language.entries.forEach { language ->
                    draw(state, dark, WIDTH, HEIGHT, language = language)
                    draw(state, dark, SHORT_PHONE_WIDTH, SHORT_PHONE_HEIGHT, language = language)
                    WIDE_SIZES.forEach { (width, height) -> draw(state, dark, width, height, language = language) }
                }
            }
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
                val needed = heightNeeded(state, WIDTH, language)
                assertTrue(needed <= SHORT_PHONE_HEIGHT, "$state in $language needs $needed of $SHORT_PHONE_HEIGHT")
            }
        }
    }

    /**
     * On its side a phone has the cards side by side over the row (CLAUDE.md §8d, *Wide screens*), and
     * each still holds a question of two lines an option with its percentage once revealed, in every
     * language, on the least height a phone on its side gives the screen. Measured wider than drawn, by
     * the share the portrait test above adds to 375, for CI's wider fonts.
     */
    @Test
    fun `every state fits a phone on its side without squeezing the option cards`() {
        (statesOf(QUESTION) + statesOf(ONE_LINE_QUESTION)).forEach { state ->
            Language.entries.forEach { language ->
                listOf(
                    PHONE_ON_ITS_SIDE_WIDTH to PHONE_ON_ITS_SIDE_HEIGHT,
                    SE_ON_ITS_SIDE_WIDTH to SE_ON_ITS_SIDE_HEIGHT,
                ).forEach { (width, height) ->
                    val needed = heightNeeded(state, width * WIDTH / SHORT_PHONE_WIDTH, language = language)
                    assertTrue(needed <= height, "$state in $language needs $needed of $height at $width wide")
                }
            }
        }
    }

    /**
     * An option as long as a question may have, 200 characters, on both cards, fits its card whole on
     * an iPhone SE, asked and revealed with its percentage under it, stacked, and on one on its side,
     * side by side: its type shrunk in steps, never below the floor, and nothing of it cut short or cut
     * off, in every language. Measured wider than drawn, as the fit tests above are, for CI's fonts.
     */
    @Test
    fun `an option of 200 characters fits its card whole on a short phone`() {
        listOf(LONG_QUESTION.optionA, LONG_QUESTION.optionB).forEach { option ->
            assertEquals(SubmissionRules.MAX_OPTION_LENGTH, option.length, option)
        }
        val states = listOf(PlayUiState.Asking(LONG_QUESTION), PlayUiState.Revealed(LONG_QUESTION, OUTCOME))
        listOf(
            WIDTH to SHORT_PHONE_HEIGHT,
            SE_ON_ITS_SIDE_WIDTH * WIDTH / SHORT_PHONE_WIDTH to SE_ON_ITS_SIDE_HEIGHT,
        ).forEach { (width, height) ->
            Language.entries.forEach { language ->
                states.forEach { state ->
                    withScreen(state, language = language, width = width, height = height) { scene, _ ->
                        scene.renderAt(COUNTED_UP)
                        scene.assertLongOptionsWhole(state, language, "$state in $language at $width by $height")
                    }
                }
            }
        }
    }

    /**
     * An option that fits at the largest size stays at it, and a long one shrinks where it must, on
     * the reveal, where its percentage takes room: told by its lines, as tall as its type is large.
     */
    @Test
    fun `only an option that needs it shrinks`() {
        withScreen(PlayUiState.Revealed(QUESTION, OUTCOME)) { scene, _ ->
            scene.renderAt(COUNTED_UP)
            listOf(QUESTION.optionA, QUESTION.optionB).forEach { option ->
                val layout = textLayoutOf(scene.everyNode().single { option in it.texts })
                assertEquals(lineAt(WyrTypeScale.optionText), lineOf(layout), 1f, option)
            }
        }
        withScreen(PlayUiState.Revealed(LONG_QUESTION, OUTCOME)) { scene, _ ->
            scene.renderAt(COUNTED_UP)
            listOf(LONG_QUESTION.optionA, LONG_QUESTION.optionB).forEach { option ->
                val layout = textLayoutOf(scene.everyNode().single { option in it.texts })
                assertTrue(lineOf(layout) < lineAt(WyrTypeScale.optionText) - 1f, "${lineOf(layout)}: $option")
            }
        }
    }

    /**
     * On a phone on its side the cards stand side by side, card A first, sharing the width, and the
     * row runs under them across it, the points under card A, Skip under card B, and the thumbs in the
     * middle of the screen.
     */
    @Test
    fun `on a phone on its side the cards stand side by side over the row`() {
        val strings = stringsOf(Language.DEFAULT).playScreen
        val asked = PlayUiState.Asking(REACTED_TO)
        withScreen(asked, width = PHONE_ON_ITS_SIDE_WIDTH, height = PHONE_ON_ITS_SIDE_HEIGHT) { scene, _ ->
            val cardA = scene.node(REACTED_TO.optionA).boundsInRoot
            val cardB = scene.node(REACTED_TO.optionB).boundsInRoot
            assertTrue(cardA.top == cardB.top && cardA.bottom == cardB.bottom, "$cardA beside $cardB")
            assertTrue(cardA.right < cardB.left && abs(cardA.width - cardB.width) <= 1f, "$cardA beside $cardB")

            val row =
                listOf(POINTS_SHOWN, strings.like, "$DISLIKES", strings.skip).map { scene.node(it).boundsInRoot }
            row.forEach { part -> assertTrue(part.top >= cardA.bottom, "$part is not under the cards") }
            val (points, like, dislikes, skip) = row
            assertTrue(points.left >= cardA.left && points.right < cardA.right, "the points at $points")
            assertTrue(skip.left > cardB.left && skip.right <= cardB.right, "Skip at $skip")
            val thumbsMiddle = (like.left - TOUCH_INSET + dislikes.right) / 2
            assertTrue(abs(thumbsMiddle - PHONE_ON_ITS_SIDE_WIDTH / 2f) <= 1f, "the thumbs are about $thumbsMiddle")
        }
    }

    /**
     * On a phone on its side the row is under both cards, so each card's bar stands along its bottom
     * edge, by the row: card B's too, which stands along its top while the cards are stacked.
     */
    @Test
    fun `on a phone on its side each card's bar stands along its bottom edge`() {
        val revealed = PlayUiState.Revealed(QUESTION, OUTCOME)
        withScreen(revealed, width = PHONE_ON_ITS_SIDE_WIDTH, height = PHONE_ON_ITS_SIDE_HEIGHT) { scene, _ ->
            scene.renderAt(COUNTED_UP)
            scene.renderAt(COUNTED_UP)
            val pixels = scene.render(COUNTED_UP).toComposeImageBitmap().toPixelMap()
            val cardB = scene.node(QUESTION.optionB).boundsInRoot
            val fill = WyrLightColors.onOptionB.toArgb()

            fun filledAt(y: Float): Int =
                (cardB.left.toInt() + CORNER until cardB.right.toInt() - CORNER).count { x ->
                    pixels[x, y.toInt()].toArgb() ==
                        fill
                }

            assertTrue(filledAt(cardB.bottom - BAR_HEIGHT / 2) > 0, "no bar along card B's bottom")
            assertEquals(0, filledAt(cardB.top + BAR_HEIGHT / 2), "a bar along card B's top")
        }
    }

    /**
     * A failed reaction says so in the points' place, so no line of its own moves the cards; and at
     * any font size, since text grows with the phone's font size and a thumb's touch target does not.
     */
    @Test
    fun `a failed reaction takes no height`() {
        val asked = PlayUiState.Asking(QUESTION)
        val revealed = PlayUiState.Revealed(QUESTION, OUTCOME)
        FONT_SCALES.forEach { fontScale ->
            Language.entries.forEach { language ->
                REACTION_FAILURES.forEach { error ->
                    val at = "in $language at font scale $fontScale"
                    assertEquals(
                        heightNeeded(asked, WIDTH, language = language, fontScale = fontScale),
                        heightNeeded(
                            asked.copy(rowError = error),
                            WIDTH,
                            language = language,
                            fontScale = fontScale,
                        ),
                        "asked with $error $at",
                    )
                    assertEquals(
                        heightNeeded(revealed, WIDTH, language = language, fontScale = fontScale),
                        heightNeeded(
                            revealed.copy(rowError = error),
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
     * counts and no Next question; the points a coin and the number, which a screen reader hears in
     * words; Skip, named for a screen reader, only while a question is asked. Read once the reveal
     * has counted up.
     */
    @Test
    fun `every state shows its texts and nothing else in every language`() {
        Language.entries.forEach { language ->
            val shown = stringsOf(language)
            expectedOf(shown, shown.points.fill(POINTS)).forEach { (state, expected) ->
                val (texts, names) = expected
                withScreen(state, language = language) { scene, _ ->
                    scene.renderAt(COUNTED_UP)
                    assertEquals(texts.sorted(), scene.texts().sorted(), "$state in $language")
                    assertEquals(names, scene.descriptions(), "$state in $language")
                }
            }
            // The points are the server's, and there are none until it has said.
            withScreen(PlayUiState.Asking(QUESTION), points = null, language = language) { scene, _ ->
                assertEquals(listOf(QUESTION.optionA, "0", "0", QUESTION.optionB).sorted(), scene.texts().sorted())
                val strings = shown.playScreen
                assertEquals(listOf(strings.like, strings.dislike, strings.skip), scene.descriptions(), "in $language")
            }
        }
    }

    /**
     * A question's options in Serbian Latin are made Latin, as every Serbian Latin text is, and in
     * Cyrillic and English shown as stored (CLAUDE.md §8f), asked and revealed.
     */
    @Test
    fun `a question's options show in Latin in Serbian Latin and as stored otherwise`() {
        val cyrillic = QUESTION.copy(optionA = "Јести пљескавицу", optionB = "Пити бозу")
        Language.entries.forEach { language ->
            val expected =
                if (language == Language.SERBIAN_LATIN) {
                    listOf("Jesti pljeskavicu", "Piti bozu")
                } else {
                    listOf(cyrillic.optionA, cyrillic.optionB)
                }
            listOf(PlayUiState.Asking(cyrillic), PlayUiState.Revealed(cyrillic, OUTCOME)).forEach { state ->
                withScreen(state, language = language) { scene, _ ->
                    val shown = scene.texts()
                    assertTrue(shown.containsAll(expected), "$state in $language: $shown")
                }
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

    /** One action at a time: while a vote or a reaction is in flight, the cards, the thumbs and Skip are off. */
    @Test
    fun `the cards and the thumbs and Skip are off while anything is in flight`() {
        val strings = stringsOf(Language.DEFAULT).playScreen
        val parts = listOf(QUESTION.optionA, QUESTION.optionB, strings.like, strings.dislike)
        listOf(
            PlayUiState.Asking(QUESTION, isSubmitting = true),
            PlayUiState.Asking(QUESTION, isReacting = true),
            PlayUiState.Revealed(QUESTION, OUTCOME, isReacting = true),
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
     * Skip also looks off while it is off, muted as the top bar drew it, so a tap that would do
     * nothing does not look like one that skips; in both themes and every language. Told by the
     * colours of its own pixels: the heading's accent and never the muted grey while it skips, and
     * the grey and never the accent while a vote or a reaction is in flight.
     */
    @Test
    fun `Skip is drawn muted while anything is in flight`() {
        listOf(WyrLightColors, WyrDarkColors).forEach { colors ->
            val accent = colors.headingAccent.toArgb()
            val muted = colors.muted.toArgb()
            Language.entries.forEach { language ->
                val at = "in $language in the ${if (colors.isDark) "dark" else "light"} theme"
                val on = skipColours(PlayUiState.Asking(QUESTION), colors.isDark, language)
                assertTrue(accent in on && muted !in on, "Skip on $at")
                listOf(
                    PlayUiState.Asking(QUESTION, isSubmitting = true),
                    PlayUiState.Asking(QUESTION, isReacting = true),
                ).forEach { state ->
                    val off = skipColours(state, colors.isDark, language)
                    assertTrue(muted in off && accent !in off, "Skip on $state $at")
                }
            }
        }
    }

    /**
     * Skip is in the row between the cards (CLAUDE.md §8d, *Skipping*), after the thumbs, while a
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
                assertTrue(skip.left > scene.node(strings.dislike).boundsInRoot.right, "Skip is not after the thumbs")

                scene.tap(strings.skip)

                assertEquals(listOf("skip"), actions.tapped, "in $language")
            }
            withScreen(PlayUiState.Revealed(QUESTION, OUTCOME), language = language) { scene, _ ->
                assertFalse(strings.skip in scene.descriptions(), "Skip on the reveal in $language")
            }
        }
    }

    /**
     * Skip's place is kept once it is gone, so the reveal moves nothing in the row: not the points, not
     * the thumbs the player may tap next, nor their counts.
     */
    @Test
    fun `the row does not move when the answer is revealed`() {
        val strings = stringsOf(Language.DEFAULT).playScreen
        val question = REACTED_TO
        val parts = listOf(POINTS_SHOWN, strings.like, "$LIKES", strings.dislike, "$DISLIKES")
        listOf(
            SHORT_PHONE_WIDTH to SHORT_PHONE_HEIGHT,
            PHONE_ON_ITS_SIDE_WIDTH to PHONE_ON_ITS_SIDE_HEIGHT,
        ).forEach { (width, height) ->
            val asked = mutableListOf<Rect>()
            withScreen(PlayUiState.Asking(question), width = width, height = height) { scene, _ ->
                parts.mapTo(asked) { scene.node(it).boundsInRoot }
            }
            withScreen(PlayUiState.Revealed(question, OUTCOME), width = width, height = height) { scene, _ ->
                assertEquals(asked, parts.map { scene.node(it).boundsInRoot }, "at $width by $height")
            }
        }
    }

    /**
     * A question the player answered before says so above the cards, in a slot of its own that every
     * question has, so nothing on the screen moves for it, asked or revealed, at any font size; and one
     * not answered before shows and says nothing there.
     */
    @Test
    fun `a question answered before says so in a slot of its own and moves nothing`() {
        val strings = stringsOf(Language.DEFAULT).playScreen
        val parts = listOf(REACTED_TO.optionA, POINTS_SHOWN, strings.like, "$DISLIKES", REACTED_TO.optionB)
        listOf<(Question) -> PlayUiState>({ PlayUiState.Asking(it) }, { PlayUiState.Revealed(it, OUTCOME) })
            .forEach { stateOf ->
                val fresh = stateOf(REACTED_TO)
                val again = stateOf(REACTED_TO.copy(answeredBefore = true))
                val before = mutableListOf<Rect>()
                withScreen(fresh) { scene, _ ->
                    assertFalse(strings.answeredBefore in scene.everyText(), "$fresh")
                    parts.mapTo(before) { scene.node(it).boundsInRoot }
                }
                withScreen(again) { scene, _ ->
                    val notice = scene.node(strings.answeredBefore).boundsInRoot
                    val cardA = scene.node(REACTED_TO.optionA).boundsInRoot
                    assertTrue(notice.bottom <= cardA.top, "the notice at $notice is not above card A at $cardA")
                    assertEquals(before, parts.map { scene.node(it).boundsInRoot }, "$again")
                }
                FONT_SCALES.forEach { fontScale ->
                    assertEquals(
                        heightNeeded(fresh, WIDTH, fontScale = fontScale),
                        heightNeeded(again, WIDTH, fontScale = fontScale),
                        "$again at font scale $fontScale",
                    )
                }
            }
    }

    /**
     * Each thumb shows whether the player holds its reaction and how many hold it, and a tap asks for
     * it, or for none when the player holds it already, so a second tap takes it back.
     */
    @Test
    fun `the thumbs show the reactions and ask for them`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).playScreen
            statesOf(QUESTION).filterIsInstance<PlayUiState.OnQuestion>().filterNot { it.isBusy }.forEach { state ->
                withScreen(state, language = language) { scene, actions ->
                    val question = state.question
                    val like = scene.node(strings.like).config.getOrNull(SemanticsProperties.ToggleableState)
                    val dislike = scene.node(strings.dislike).config.getOrNull(SemanticsProperties.ToggleableState)
                    assertEquals(toggle(question.myReaction == Reaction.LIKE), like, "$state")
                    assertEquals(toggle(question.myReaction == Reaction.DISLIKE), dislike, "$state")
                    assertTrue(question.likeCount.toString() in scene.texts(), "$state")
                    assertTrue(question.dislikeCount.toString() in scene.texts(), "$state")

                    scene.tap(strings.like)
                    scene.tap(strings.dislike)

                    val asked =
                        listOf(
                            "react ${reactionAfterTap(Reaction.LIKE, question.myReaction)}",
                            "react ${reactionAfterTap(Reaction.DISLIKE, question.myReaction)}",
                        )
                    assertEquals(asked, actions.tapped, "$state in $language")
                }
            }
        }
    }

    /**
     * A screen reader hears what a tap does where the text does not say it: a card on the reveal goes
     * on to the next question, which its option alone would make sound like answering again, and the
     * categories played, on the top bar, open the Categories screen. Before the reveal a card's option
     * says it all.
     */
    @Test
    fun `a screen reader hears what a tap on a revealed card and on the categories does`() {
        val cards = listOf(QUESTION.optionA, QUESTION.optionB)
        Language.entries.forEach { language ->
            val strings = stringsOf(language).playScreen
            val all = stringsOf(language).allCategories
            withScreen(PlayUiState.Asking(QUESTION), language = language) { scene, _ ->
                cards.forEach { assertNull(scene.clickLabel(it), "$it before the reveal in $language") }
            }
            withScreen(PlayUiState.Revealed(QUESTION, OUTCOME), language = language) { scene, _ ->
                cards.forEach { assertEquals(strings.nextQuestion, scene.clickLabel(it), "$it in $language") }
            }
            val scene =
                ImageComposeScene(width = SHORT_PHONE_WIDTH, height = ROW_HEIGHT, density = Density(1f)) {
                    WyrTheme { WyrStrings(language) { CategoriesPlayed(text = all, enabled = true, onClick = {}) } }
                }
            try {
                scene.render()
                assertEquals(strings.changeCategories, scene.clickLabel(all), "the categories in $language")
            } finally {
                scene.close()
            }
        }
    }

    /**
     * The points, the thumbs and Skip sit in one row between the two cards, in that order, the thumbs
     * in the middle of the screen.
     */
    @Test
    fun `the row sits between the cards with the thumbs in the middle`() {
        val strings = stringsOf(Language.DEFAULT).playScreen
        withScreen(PlayUiState.Asking(REACTED_TO)) { scene, _ ->
            val cardA = scene.node(REACTED_TO.optionA).boundsInRoot
            val cardB = scene.node(REACTED_TO.optionB).boundsInRoot
            val row =
                listOf(POINTS_SHOWN, strings.like, strings.dislike, "$DISLIKES", strings.skip).map {
                    scene.node(it).boundsInRoot
                }
            row.forEach { part -> assertTrue(part.center.y > cardA.bottom && part.center.y < cardB.top, "$part") }

            val (points, like, dislike, dislikes, skip) = row
            // A thumb's bounds are its icon button's, inside its touch target, which the row sets from.
            val thumbsMiddle = (like.left - TOUCH_INSET + dislikes.right) / 2
            assertTrue(abs(thumbsMiddle - SHORT_PHONE_WIDTH / 2f) <= 1f, "the thumbs are about $thumbsMiddle")
            assertTrue(points.right < like.left && like.right < dislike.left && skip.left > dislikes.right, "$row")
            assertTrue(points.left >= cardA.left && skip.right <= cardA.right, "the row is wider than a card: $row")
        }
    }

    /**
     * A reaction's failure shows in the points' place, cut short on its two lines there when it runs
     * long, never the counts or Skip; measured 400 wide, as the fit test above is.
     */
    @Test
    fun `a reaction's failure takes the points' place and leaves the rest whole`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).playScreen
            REACTION_FAILURES.forEach { error ->
                withRow(WIDTH - 2 * PADDING, language = language, rowError = error) { scene ->
                    val failure = failureText(assertNotNull(error), strings)
                    assertTrue(failure in scene.texts(), "$error in $language")
                    assertFalse(POINTS_SHOWN in scene.descriptions(), "the points make way in $language")
                    listOf("$LIKES", "$DISLIKES").forEach { assertFalse(scene.isCutShort(it), "$it in $language") }
                    assertTrue(strings.skip in scene.descriptions())
                }
            }
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

    /**
     * A failure offers Try again; a selection with nothing to serve ends here too, and the categories
     * played on the top bar are the way out of it (`TopBarsDrawTest`).
     */
    @Test
    fun `a failure offers Try again`() {
        Language.entries.forEach { language ->
            withScreen(PlayUiState.Failed(DomainError.OUT_OF_QUESTIONS), language = language) { scene, actions ->
                scene.tap(stringsOf(language).tryAgain)
                assertEquals(listOf("retry"), actions.tapped, "in $language")
                assertFalse(stringsOf(language).allCategories in scene.texts(), "the categories are on the top bar")
            }
        }
    }

    /**
     * Both percentages count up from 0 at once and reach their values at 2.5 seconds, on the scene's
     * clock stepped a frame at a time as a phone's is, in both themes. The count is drawn, not a text
     * that changes, so what each card shows is told by its pixels where its percentage is: *0%* at the
     * start, centred there, a number between a quarter of the way in (both still climbing together, CLAUDE.md
     * §8d, *The Play screen*), and at the end its value drawn exactly as the
     * text it replaced drew it, the same font, size, weight, colour and place, and nothing moving after.
     */
    @Test
    fun `the reveal's percentages count up from 0 over two and a half seconds`() {
        val strings = stringsOf(Language.DEFAULT).playScreen
        // Each card while it is the pick: the other stands faint, in colours of its own.
        forEachPickAndTheme { pick, colors, theme ->
            withScreen(
                PlayUiState.Revealed(QUESTION, OUTCOME.copy(yourSide = pick)),
                dark = colors.isDark,
            ) { scene, _ ->
                val cards =
                    listOf(
                        Triple(strings.percent(70), colors.optionA, colors.onOptionA),
                        Triple(strings.percent(30), colors.optionB, colors.onOptionB),
                    )
                val areas = cards.map { (shown, _, _) -> scene.everyNode().single { shown in it.texts }.boundsInRoot }

                val start = scene.pixelsAt(0, areas)
                val halfway = scene.pixelsAt(CLIMBING, areas, from = 0)
                val end = scene.pixelsAt(COUNTED_UP, areas, from = CLIMBING)
                cards.zip(areas).forEachIndexed { card, (colours, area) ->
                    if (card != pick.ordinal) return@forEachIndexed
                    val (shown, background, text) = colours
                    val at = "card ${card + 1} in the $theme theme"
                    assertEquals(
                        textPixels(strings.percent(0), area, background, text),
                        start[card],
                        "$at at the start",
                    )
                    assertTrue(
                        halfway[card] != start[card] && halfway[card] != end[card],
                        "$at a quarter of the way in",
                    )
                    assertEquals(textPixels(shown, area, background, text), end[card], "$at at 2.5 seconds")
                }
                val after = scene.pixelsAt(COUNTED_UP * 2, areas, from = COUNTED_UP)
                assertEquals(end, after, "after, in the $theme theme")
            }
        }
    }

    /**
     * Each card's bar, along its edge by the row, fills from its start with the count, over the same
     * two and a half seconds, and stops where the card's share does: empty at the start, part way
     * a quarter of the way in, and at 2.5 seconds filled to its share of the card's width, then still. Told by the
     * pixels along the middle of each bar: the card's text colour where it is filled.
     */
    @Test
    fun `each card's bar fills with its share over two and a half seconds`() {
        // Each card while it is the pick: the other stands faint, in colours of its own.
        forEachPickAndTheme { pick, colors, theme ->
            withScreen(
                PlayUiState.Revealed(QUESTION, OUTCOME.copy(yourSide = pick)),
                dark = colors.isDark,
            ) { scene, _ ->
                val cardA = scene.node(QUESTION.optionA).boundsInRoot
                val cardB = scene.node(QUESTION.optionB).boundsInRoot
                // Along the middle of each bar, flush along its card's edge by the row.
                val bars =
                    listOf(
                        Triple(cardA, cardA.bottom - BAR_HEIGHT / 2, colors.onOptionA),
                        Triple(cardB, cardB.top + BAR_HEIGHT / 2, colors.onOptionB),
                    )

                // How far each bar is filled, as a share of its card's width: from past the card's round
                // corner, as far right as the fill runs unbroken; null for none.
                fun filledTo(nanoTime: Long): List<Float?> {
                    scene.renderAt(nanoTime)
                    val pixels = scene.render(nanoTime).toComposeImageBitmap().toPixelMap()
                    return bars.map { (card, y, fill) ->
                        val from = card.left.toInt() + CORNER
                        val to =
                            (from until card.right.toInt())
                                .takeWhile { x -> pixels[x, y.toInt()].toArgb() == fill.toArgb() }
                                .lastOrNull()
                        to?.let { (it + 1 - card.left) / card.width }
                    }
                }

                val start = filledTo(0)
                val halfway = filledTo(CLIMBING)
                val end = filledTo(COUNTED_UP)
                val after = filledTo(COUNTED_UP * 2)
                assertEquals(null, start[pick.ordinal], "nothing filled at the start in the $theme theme")
                listOf(0.7f, 0.3f).forEachIndexed { card, share ->
                    if (card != pick.ordinal) return@forEachIndexed
                    val at = "card ${card + 1} in the $theme theme"
                    val half = assertNotNull(halfway[card], "$at a quarter of the way in")
                    assertTrue(half > 0f && half < share, "$at is filled to $half a quarter of the way in")
                    val filled = assertNotNull(end[card], "$at at 2.5 seconds")
                    assertTrue(abs(filled - share) <= BAR_TOLERANCE, "$at is filled to $filled of $share")
                }
                assertEquals(end, after, "still after, in the $theme theme")
            }
        }
    }

    /**
     * Going on is one smooth change, never a snap to a spinner (CLAUDE.md §8d, *The Play screen*): while
     * the next question loads the one answered stays on screen, and only past [LOADING_GRACE] does the
     * spinner show; the next question then slides onto the same cards, the face going heard by nobody,
     * and once it has gone only the new question is on screen.
     */
    @Test
    fun `the next question slides onto the cards with no spinner between`() {
        val strings = stringsOf(Language.DEFAULT)
        val next = QUESTION.copy(id = "q2", optionA = "Swim like a fish", optionB = "Run like a cheetah")
        var state by mutableStateOf<PlayUiState>(PlayUiState.Revealed(QUESTION, OUTCOME))
        val scene =
            ImageComposeScene(width = SHORT_PHONE_WIDTH, height = SHORT_PHONE_HEIGHT, density = Density(1f)) {
                WyrTheme(darkTheme = false) { WyrStrings(Language.DEFAULT) { Screen(state, POINTS) } }
            }
        try {
            scene.renderAt(0)
            state = PlayUiState.Loading
            scene.renderAt(FRAME)
            assertTrue(QUESTION.optionA in scene.texts(), "the question stays while the next loads")
            assertTrue(strings.loading !in scene.descriptions(), "no spinner while the next loads")

            state = PlayUiState.Asking(next)
            scene.renderAt(2 * FRAME)
            val heard = scene.texts()
            assertTrue(next.optionA in heard && next.optionB in heard, "the next question is heard at once")
            assertTrue(QUESTION.optionA !in heard, "the question going is heard by nobody: $heard")
            assertTrue(QUESTION.optionA in scene.everyText(), "the question going is still drawn as it goes")

            val gone = 2 * FRAME + (FACE_OUT_MILLIS + FACE_IN_MILLIS) * 1_000_000L + FRAME
            scene.renderAt(gone)
            assertTrue(QUESTION.optionA !in scene.everyText(), "the question before has gone")

            state = PlayUiState.Loading
            scene.renderAt(gone + FRAME)
            assertTrue(strings.loading !in scene.descriptions(), "no spinner within the grace")
            scene.renderAt(gone + FRAME + LOADING_GRACE.inWholeNanoseconds + FRAME)
            assertTrue(strings.loading in scene.descriptions(), "the spinner once the next is slow to come")
        } finally {
            scene.close()
        }
    }

    /**
     * A screen reader reads each card's share as it is, never a number the count passes through: the
     * count is only drawn, and the text it stands for is the final percentage from its first frame.
     */
    @Test
    fun `a screen reader reads each card's final percentage throughout the count up`() {
        Language.entries.forEach { language ->
            val strings = stringsOf(language).playScreen
            withScreen(PlayUiState.Revealed(QUESTION, OUTCOME), language = language) { scene, _ ->
                listOf(0L, COUNTED_UP / 4, COUNTED_UP / 2, COUNTED_UP).forEach { time ->
                    scene.renderAt(time)
                    assertEquals(
                        listOf(strings.percent(70), strings.percent(30)),
                        percentages(scene),
                        "at ${time / 1_000_000} ms in $language",
                    )
                }
            }
        }
    }

    /**
     * A frame of the count up only draws the two numbers again: nothing is composed again and nothing
     * on the screen is measured anew or moved, however many frames it takes. A debug build, whose
     * Compose runs several times slower, has no time for more in a frame at 60 a second. Before, the
     * count was a text changed every frame, which recomposed both cards, laid their texts out again
     * as they widened, and told any accessibility service on the phone of every change.
     */
    @Test
    fun `a frame of the count up composes nothing and moves nothing`() {
        val recompositions = Recompositions()
        withScreen(PlayUiState.Revealed(QUESTION, OUTCOME), recompositions = recompositions) { scene, _ ->
            scene.renderAt(FRAME)
            val composed = recompositions.scopesEntered
            val laidOut = scene.everyNode().map { it.boundsInRoot }
            val frames = 2..COUNTED_UP / FRAME + 1
            frames.forEach { frame ->
                scene.renderAt(frame * FRAME)
                assertEquals(composed, recompositions.scopesEntered, "scopes composed by frame $frame")
                assertEquals(laidOut, scene.everyNode().map { it.boundsInRoot }, "the screen at frame $frame")
            }
            recompositions.assertCounting(scene, frames.last * FRAME)
        }
    }

    /**
     * At a short phone's width, less the screen's padding, the row needs no more width than it has,
     * so nothing in it is cut short: not the points, not the counts, not how a reaction failed, in any
     * language; and it is the same height whatever it shows.
     */
    @Test
    fun `nothing in the row is cut short at a short phone's width`() {
        val question = QUESTION.copy(likeCount = 1234, dislikeCount = 567, myReaction = Reaction.LIKE)
        Language.entries.forEach { language ->
            (REACTION_FAILURES + null).forEach { error ->
                // Asked, with Skip, and answered, with its place kept.
                listOf<(() -> Unit)?>({}, null).forEach { onSkip ->
                    val at = "with $error in $language ${if (onSkip == null) "answered" else "asked"}"
                    val (width, height) =
                        sizeNeeded(ROW_WIDTH, SHORT_PHONE_HEIGHT) {
                            WyrTheme {
                                WyrStrings(language) {
                                    MiddleRow(
                                        question = question,
                                        points = 12345,
                                        rowError = error,
                                        idle = true,
                                        onReact = {},
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

    /** The percentages the scene shows, card A's first. */
    private fun percentages(scene: ImageComposeScene): List<String> = scene.texts().filter { it.endsWith("%") }

    /**
     * The colours, as ARGB, of every pixel inside each of [areas], drawn at [nanoTime], after every
     * frame since [from] at 60 a second, as a phone draws them.
     */
    private fun ImageComposeScene.pixelsAt(
        nanoTime: Long,
        areas: List<Rect>,
        from: Long = nanoTime,
    ): List<List<Int>> {
        generateSequence(from + FRAME) { it + FRAME }.takeWhile { it < nanoTime }.forEach { renderAt(it) }
        renderAt(nanoTime)
        val pixels = render(nanoTime).toComposeImageBitmap().toPixelMap()
        return areas.map { pixels.inside(it) }
    }

    /**
     * The pixels of [text] as the cards drew a percentage before its count was drawn: a `Text` of the
     * percentage's size and weight in [color], at the top of a box the size of [area] filled with
     * [background], and in the middle of it.
     */
    private fun textPixels(
        text: String,
        area: Rect,
        background: Color,
        color: Color,
    ): List<Int> {
        val scene =
            ImageComposeScene(width = area.width.toInt(), height = area.height.toInt(), density = Density(1f)) {
                WyrTheme {
                    Box(Modifier.fillMaxSize().background(background), contentAlignment = Alignment.TopCenter) {
                        Text(text, color = color, fontSize = WyrTypeScale.percentage, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        try {
            return scene
                .render()
                .toComposeImageBitmap()
                .toPixelMap()
                .inside(Rect(Offset.Zero, area.size))
        } finally {
            scene.close()
        }
    }

    /** The colours, as ARGB, of every pixel inside [area], row by row. */
    private fun PixelMap.inside(area: Rect): List<Int> =
        (area.top.toInt() until area.bottom.toInt()).flatMap { y ->
            (area.left.toInt() until area.right.toInt()).map { x -> this[x, y].toArgb() }
        }

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

    /** The colours, as ARGB, of every pixel inside Skip's button on [state]'s screen. */
    private fun skipColours(
        state: PlayUiState,
        dark: Boolean,
        language: Language,
    ): Set<Int> {
        val colours = mutableSetOf<Int>()
        withScreen(state, language = language, dark = dark) { scene, _ ->
            val skip = scene.node(stringsOf(language).playScreen.skip).boundsInRoot
            val pixels = scene.render().toComposeImageBitmap().toPixelMap()
            for (x in skip.left.toInt() until skip.right.toInt()) {
                for (y in skip.top.toInt() until skip.bottom.toInt()) colours += pixels[x, y].toArgb()
            }
        }
        return colours
    }

    /**
     * That both of [LONG_QUESTION]'s options, as [state] shows them in [language], are laid out whole in
     * their cards, at a size no smaller than the floor, and on the reveal each card's percentage too.
     */
    private fun ImageComposeScene.assertLongOptionsWhole(
        state: PlayUiState,
        language: Language,
        at: String,
    ) {
        val strings = stringsOf(language).playScreen
        listOf(
            LONG_QUESTION.optionA to strings.percent(70),
            LONG_QUESTION.optionB to strings.percent(30),
        ).forEach { (option, percent) ->
            val shown = optionText(option, language)
            val card = node(shown).boundsInRoot
            val text = everyNode().single { shown in it.texts }
            val layout = textLayoutOf(text)
            assertFalse(layout.hasVisualOverflow, "$at: the option overflows")
            assertFalse(layout.multiParagraph.didExceedMaxLines, "$at: the option is cut short")
            // Told by its lines, as tall as its type is large: its style names the largest size.
            assertTrue(lineOf(layout) >= lineAt(WyrTypeScale.optionTextMin) - 1f, "$at: ${lineOf(layout)}")
            assertTrue(card.containsWhole(text.laidOut()), "$at: ${text.laidOut()} is not in $card")
            if (state is PlayUiState.Revealed) {
                val counted = everyNode().single { percent in it.texts }.laidOut()
                assertTrue(card.containsWhole(counted), "$at: $percent at $counted is not in $card")
            }
        }
    }

    /** The layout of [node]'s text, as the text it shows last laid it out. */
    private fun textLayoutOf(node: SemanticsNode): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        val layout = assertNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action, "$node")
        layout(layouts)
        return layouts.single()
    }

    /** The height of [layout]'s first line, at one pixel a dp and the font's own size. */
    private fun lineOf(layout: TextLayoutResult): Float = layout.getLineBottom(0) - layout.getLineTop(0)

    /** An option's line at [size], as `WyrTypeScale.optionLineHeight` sets it, at one pixel a dp. */
    private fun lineAt(size: TextUnit): Float = size.value * WyrTypeScale.optionLineHeight.value

    /** Where [this] is laid out in the scene, clipped or not. */
    private fun SemanticsNode.laidOut(): Rect = Rect(positionInRoot, size.toSize())

    /** Whether [other] lies inside this whole, to the half pixel a layout rounds to. */
    private fun Rect.containsWhole(other: Rect): Boolean =
        other.left >= left - HALF_PIXEL &&
            other.top >= top - HALF_PIXEL &&
            other.right <= right + HALF_PIXEL &&
            other.bottom <= bottom + HALF_PIXEL

    /** Whether the one node showing [text] cuts it short: it needs more lines than it may take. */
    private fun ImageComposeScene.isCutShort(text: String): Boolean {
        val layouts = mutableListOf<TextLayoutResult>()
        val layout = assertNotNull(node(text).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action, text)
        layout(layouts)
        // Skia's paragraphs on the desktop never report a line ellipsized, so the lines it needed.
        return layouts.single().multiParagraph.didExceedMaxLines
    }

    /**
     * [test] on the row alone, [width] wide, in [language], asked with Skip: [POINTS], or how a
     * reaction failed, [rowError], and [LIKES] likes and [DISLIKES] dislikes.
     */
    private fun withRow(
        width: Int,
        language: Language = Language.DEFAULT,
        rowError: DomainError? = null,
        test: (ImageComposeScene) -> Unit,
    ) {
        val scene =
            ImageComposeScene(width = width, height = ROW_HEIGHT, density = Density(1f)) {
                WyrTheme {
                    WyrStrings(language) {
                        MiddleRow(
                            question = REACTED_TO,
                            points = POINTS,
                            rowError = rowError,
                            idle = true,
                            onReact = {},
                            onSkip = {},
                        )
                    }
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

    /** A thumb's toggle as a screen reader hears it: on while the player holds its reaction. */
    private fun toggle(on: Boolean): ToggleableState = if (on) ToggleableState.On else ToggleableState.Off

    /** What the screen's callbacks were called for, in order. */
    private class Actions {
        val tapped = mutableListOf<String>()
    }

    /** Runs [test] for each side as the pick, in the light theme and the dark. */
    private fun forEachPickAndTheme(test: (Side, WyrColors, String) -> Unit) {
        Side.entries.forEach { pick ->
            listOf(WyrLightColors, WyrDarkColors).forEach { colors ->
                test(pick, colors, if (colors.isDark) "dark" else "light")
            }
        }
    }

    /**
     * [test] on [state]'s screen at [width] by [height], the short phone's size unless told, in the
     * light theme unless [dark], drawn at time 0, its composition counted by [recompositions].
     */
    private fun withScreen(
        state: PlayUiState,
        points: Int? = POINTS,
        language: Language = Language.DEFAULT,
        dark: Boolean = false,
        recompositions: Recompositions = Recompositions(),
        width: Int = SHORT_PHONE_WIDTH,
        height: Int = SHORT_PHONE_HEIGHT,
        test: (ImageComposeScene, Actions) -> Unit,
    ) {
        val actions = Actions()
        val scene =
            ImageComposeScene(width = width, height = height, density = Density(1f)) {
                CountedBy(recompositions) {
                    WyrTheme(darkTheme = dark) {
                        WyrStrings(language) { Screen(state, points, actions) }
                    }
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
        language: Language = Language.DEFAULT,
    ) {
        val scene =
            ImageComposeScene(width = width, height = height, density = Density(1f)) {
                WyrTheme(darkTheme = dark) { WyrStrings(language) { Screen(state, POINTS) } }
            }
        try {
            assertEquals(width, scene.render().width)
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
        language: Language = Language.DEFAULT,
        fontScale: Float = 1f,
    ): Int =
        heightNeeded(width, "$state", fontScale) {
            WyrStrings(language) { Screen(state, POINTS) }
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
        points: Int?,
        actions: Actions = Actions(),
    ) {
        PlayScreen(
            state = state,
            points = points,
            onChoose = { side -> actions.tapped += "choose $side" },
            onSkip = { actions.tapped += "skip" },
            onNext = { actions.tapped += "next" },
            onReact = { reaction -> actions.tapped += "react $reaction" },
            onRetry = { actions.tapped += "retry" },
        )
    }

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 900

        /** An iPhone SE (667 high) less its status bar (20) and the top bar above the Play screen (48). */
        const val SHORT_PHONE_WIDTH = 375
        const val SHORT_PHONE_HEIGHT = 599

        /**
         * A phone of 360 by 780 on its side, a Galaxy S23's size, less its status bar, the gesture areas
         * at its ends and foot, and the top bar above the Play screen: about the least room a phone held
         * that way gives the screen.
         */
        const val PHONE_ON_ITS_SIDE_WIDTH = 720
        const val PHONE_ON_ITS_SIDE_HEIGHT = 256

        /** An iPhone SE on its side (667 by 375, which shows no status bar so), less the top bar. */
        const val SE_ON_ITS_SIDE_WIDTH = 667
        const val SE_ON_ITS_SIDE_HEIGHT = 327

        /** Two phones on their side, and a desktop window as Compose first opens one (800 by 600) less the top bar. */
        val WIDE_SIZES: List<Pair<Int, Int>> =
            listOf(
                PHONE_ON_ITS_SIDE_WIDTH to PHONE_ON_ITS_SIDE_HEIGHT,
                SE_ON_ITS_SIDE_WIDTH to SE_ON_ITS_SIDE_HEIGHT,
                800 to 552,
            )

        /** The screen's padding on each side (`WyrDimens.screenPadding`). */
        const val PADDING = 20

        /** The short phone's width less the screen's padding on each side. */
        const val ROW_WIDTH = SHORT_PHONE_WIDTH - 2 * PADDING

        /** A thumb's touch target and the row's padding above and below it. */
        const val ROW_HEIGHT = 56

        /** How far an icon button's bounds are inside its touch target, 40 of 48, on each side. */
        const val TOUCH_INSET = 4f

        /** The reveal's bar's height (`WyrDimens.revealBarHeight`). */
        const val BAR_HEIGHT = 8f

        /** Past a card's round corner, where a bar's fill shows first. */
        const val CORNER = 14

        /** How far a bar's fill may end from its share of the card's width: a pixel and its rounding. */
        const val BAR_TOLERANCE = 0.01f

        /** How far a layout's rounding may put one edge past another. */
        const val HALF_PIXEL = 0.5f

        /**
         * A quarter of the count's time, in nanoseconds: both sides still climbing together, short of
         * the smaller share (70 against 30 climbs together for 43% of the time).
         */
        const val CLIMBING = COUNT_UP_MILLIS * 1_000_000L / 4

        /** The scene's clock, in nanoseconds, once the reveal has counted up. */
        const val COUNTED_UP = COUNT_UP_MILLIS * 1_000_000L

        /** One frame of a phone drawing 60 a second, in nanoseconds. */
        const val FRAME = 1_000_000_000L / 60

        const val POINTS = 42

        /**
         * [POINTS] as a screen reader hears them in the default language, which the scenes are drawn
         * in unless told: on screen they are a coin and the number.
         */
        val POINTS_SHOWN = stringsOf(Language.DEFAULT).points.fill(POINTS)

        /** A like count of a question many like, and its dislike count, neither the other's. */
        const val LIKES = 12
        const val DISLIKES = 5

        /** Two lines an option on a phone, as most seeds are. */
        val QUESTION =
            Question(
                id = "q1",
                optionA = "Be able to fly but only a metre off the ground",
                optionB = "Turn invisible but only while nobody is looking",
                categories = setOf("SUPERPOWERS"),
            )
        val ONE_LINE_QUESTION = QUESTION.copy(optionA = "Fly", optionB = "Swim")

        /** Both options as long as a question's may be, 200 characters, in Serbian, as a player would write. */
        val LONG_QUESTION =
            QUESTION.copy(
                optionA =
                    "Да ти до краја живота сваки пут кад кинеш неко из публике аплаудира, а кад се насмејеш да сви " +
                        "у близини почну да играју коло, чак и на сахрани, у болници, на испиту или усред важног " +
                        "састанка на послу.",
                optionB =
                    "Да сваки пут кад отвориш фрижидер у њему нађеш тачно оно што ти се тог тренутка једе, али " +
                        "само ако пре тога наглас отпеваш целу химну уназад, на ногама, пред свима који су у кући и " +
                        "пред свим комшијама",
            )

        /** [QUESTION] with [LIKES] likes and [DISLIKES] dislikes, neither the player's. */
        val REACTED_TO = QUESTION.copy(likeCount = LIKES, dislikeCount = DISLIKES)

        /** The phone's font size as it is, Android's largest before Android 14, and twice it, the largest since. */
        val FONT_SCALES: List<Float> = listOf(1f, 1.3f, 2f)

        /** Every way a reaction's failure is worded, the longest among them. */
        val REACTION_FAILURES: List<DomainError?> =
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
                PlayUiState.Asking(question.copy(likeCount = 1, myReaction = Reaction.LIKE), isReacting = true),
                PlayUiState.Asking(question.copy(likeCount = 12), rowError = DomainError.NETWORK),
                PlayUiState.Asking(question.copy(likeCount = 4, dislikeCount = 2, myReaction = Reaction.DISLIKE)),
                PlayUiState.Revealed(question, OUTCOME),
                PlayUiState.Revealed(question, OUTCOME.copy(pointsAwarded = 0, replayed = true)),
                PlayUiState.Revealed(question.copy(likeCount = 3), OUTCOME, isReacting = true),
                PlayUiState.Revealed(
                    question.copy(likeCount = 1234, dislikeCount = 99, myReaction = Reaction.LIKE),
                    OUTCOME,
                ),
                PlayUiState.Revealed(question, OUTCOME, rowError = DomainError.NETWORK),
                PlayUiState.Revealed(question, OUTCOME, rowError = DomainError.QUESTION_NOT_FOUND),
                PlayUiState.Asking(question.copy(answeredBefore = true)),
                PlayUiState.Revealed(question.copy(answeredBefore = true), OUTCOME),
            )

        /**
         * What some states show in [shown]'s words, each its texts in any order, and then the names it
         * gives a screen reader for what has no text, from the top down: the thumbs and, while a
         * question is asked, Skip, and then [points], as it hears the points, which sit a little lower,
         * in the middle of the row's height, as the thumbs' touch targets fill it.
         */
        fun expectedOf(
            shown: Strings,
            points: String,
        ): List<Pair<PlayUiState, Pair<List<String>, List<String>>>> {
            val strings = shown.playScreen
            val tryAgain = shown.tryAgain
            val a = QUESTION.optionA
            val b = QUESTION.optionB
            val revealedA = strings.percent(70)
            val revealedB = strings.percent(30)
            val thumbs = listOf(strings.like, strings.dislike)
            val thumbsAndSkip = thumbs + strings.skip
            return listOf(
                PlayUiState.Loading to (emptyList<String>() to listOf(shown.loading)),
                PlayUiState.Failed(DomainError.NETWORK) to (listOf(strings.cannotReach, tryAgain) to emptyList()),
                PlayUiState.Failed(DomainError.OUT_OF_QUESTIONS) to
                    (listOf(strings.outOfQuestions, tryAgain) to emptyList()),
                PlayUiState.Failed(DomainError.SERVER) to (listOf(strings.somethingWrong, tryAgain) to emptyList()),
                PlayUiState.Asking(QUESTION) to (listOf(a, "0", "0", b) to thumbsAndSkip + points),
                PlayUiState.Asking(QUESTION.copy(likeCount = 12), rowError = DomainError.NETWORK) to
                    (listOf(a, strings.cannotReach, "12", "0", b) to thumbsAndSkip),
                PlayUiState.Revealed(QUESTION, OUTCOME) to
                    (listOf(a, revealedA, "0", "0", b, revealedB) to thumbs + points),
                PlayUiState.Asking(QUESTION.copy(answeredBefore = true)) to
                    (listOf(strings.answeredBefore, a, "0", "0", b) to thumbsAndSkip + points),
                PlayUiState.Revealed(QUESTION.copy(answeredBefore = true), OUTCOME) to
                    (listOf(strings.answeredBefore, a, revealedA, "0", "0", b, revealedB) to thumbs + points),
                PlayUiState.Revealed(QUESTION, OUTCOME.copy(pointsAwarded = 0, replayed = true)) to
                    (listOf(a, revealedA, "0", "0", b, revealedB) to thumbs + points),
                PlayUiState.Revealed(
                    QUESTION.copy(likeCount = 3, dislikeCount = 1),
                    OUTCOME,
                    rowError = DomainError.QUESTION_NOT_FOUND,
                ) to
                    (listOf(a, revealedA, strings.questionGone, "3", "1", b, revealedB) to thumbs),
            )
        }
    }
}
