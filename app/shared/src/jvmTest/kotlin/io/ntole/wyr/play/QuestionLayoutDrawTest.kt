package io.ntole.wyr.play

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.ntole.wyr.descriptions
import io.ntole.wyr.nodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How the Play screen arranges its cards and the row ([QuestionLayout], CLAUDE.md §8d, *Wide
 * screens*), held to its rule on boxes of known sizes, which no font changes: side by side over the
 * row on a screen wider than tall and at least 600 across, stacked around the row on any other; and
 * the height it says it needs, which the Play screen's own tests ask, by the same rule.
 */
class QuestionLayoutDrawTest {
    @Test
    fun `a phone held upright has the cards stacked around the row`() {
        // 531 less the row's 56 is 475, of which card A takes the smaller half, as a Column's weights do.
        assertEquals(
            listOf(Rect(0f, 0f, 335f, 237f), Rect(0f, 237f, 335f, 293f), Rect(0f, 293f, 335f, 531f)),
            laidOut(335, 531),
        )
    }

    @Test
    fun `a phone on its side has the cards side by side over the row`() {
        // 740 less the gap of 16 is 724, 362 a card; 232 less the row's 56 is 176.
        assertEquals(
            listOf(Rect(0f, 0f, 362f, 176f), Rect(0f, 176f, 740f, 232f), Rect(378f, 0f, 740f, 176f)),
            laidOut(740, 232),
        )
    }

    @Test
    fun `the cards stand side by side from 600 across and only while the screen is wider than tall`() {
        mapOf(
            (600 to 232) to true,
            (599 to 232) to false,
            // A desktop window, less the top bar.
            (800 to 552) to true,
            (700 to 700) to false,
            // A tablet held upright.
            (760 to 1000) to false,
        ).forEach { (size, sideBySide) ->
            val (width, height) = size
            val (cardA, row, cardB) = laidOut(width, height)
            if (sideBySide) {
                assertTrue(
                    cardA.top == cardB.top && cardA.right + GAP == cardB.left && row.top == cardA.bottom,
                    "$size is not side by side: ${listOf(cardA, row, cardB)}",
                )
            } else {
                assertTrue(
                    cardA.left == cardB.left && row.top == cardA.bottom && cardB.top == row.bottom,
                    "$size is not stacked: ${listOf(cardA, row, cardB)}",
                )
            }
            assertEquals(Rect(0f, row.top, width.toFloat(), row.top + ROW), row, "the row at $size")
        }
    }

    @Test
    fun `the height it needs is the least at which neither card is squeezed`() {
        // Cards of at least 140 and a row of 56: stacked, 56 and twice 140.
        assertEquals(336, heightNeeded(335))
        assertEquals(336, heightNeeded(599))
        // Side by side, 56 and 140 once, which leaves the screen wider than tall.
        assertEquals(196, heightNeeded(600))
        assertEquals(196, heightNeeded(740))
        // Cards of 700 side by side would leave a screen 650 wide taller than wide: stacked again.
        assertEquals(1456, heightNeeded(650, cardHeight = 700))
    }

    /** Where card A, the row and card B are laid out, in that order, in a screen [width] by [height]. */
    private fun laidOut(
        width: Int,
        height: Int,
    ): List<Rect> {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) { Cards() }
        try {
            scene.render()
            return listOf("A", "row", "B").map { name -> scene.nodes().single { name in it.descriptions }.boundsInRoot }
        } finally {
            scene.close()
        }
    }

    /** The least height the layout says it needs at [width], its cards at least [cardHeight] high. */
    private fun heightNeeded(
        width: Int,
        cardHeight: Int = CARD,
    ): Int {
        var needed = -1
        val scene =
            ImageComposeScene(width = width, height = 2000, density = Density(1f)) {
                Layout(content = { Cards(cardHeight) }) { measurables, constraints ->
                    val cards = measurables.single()
                    needed = cards.minIntrinsicHeight(constraints.maxWidth)
                    val placeable = cards.measure(constraints)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
            }
        try {
            scene.render()
        } finally {
            scene.close()
        }
        return needed
    }

    @Composable
    private fun Cards(cardHeight: Int = CARD) {
        QuestionLayout(wideMinWidth = 600.dp, gap = GAP.dp, modifier = Modifier.fillMaxSize()) {
            Card("A", cardHeight)
            Box(Modifier.fillMaxWidth().height(ROW.dp).semantics { contentDescription = "row" })
            Card("B", cardHeight)
        }
    }

    @Composable
    private fun Card(
        name: String,
        height: Int,
    ) {
        Box(Modifier.fillMaxSize().heightIn(min = height.dp).semantics { contentDescription = name })
    }

    private companion object {
        /** The gap between the cards side by side (`WyrDimens.spaceMd`). */
        const val GAP = 16f

        /** The row's height: the heart's touch target and a padding of 4 above and below it. */
        const val ROW = 56f

        /** The least a card is high (`WyrDimens.optionMinHeight`). */
        const val CARD = 140
    }
}
