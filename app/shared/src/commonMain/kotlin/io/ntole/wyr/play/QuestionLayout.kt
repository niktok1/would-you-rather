package io.ntole.wyr.play

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp

/**
 * The Play screen's two answer cards and the row between them (CLAUDE.md §8d, *Wide screens*): its
 * [content] is three children, card A, the row and card B, in that order. On a screen wider than tall
 * and at least [wideMinWidth] across, a phone on its side or a desktop window, the cards stand side by
 * side, [gap] apart and card A first, sharing the width and the height the row under them leaves. On
 * any other, a phone held upright above all, they are stacked, card A on top, sharing the height the
 * row between them leaves. The row is always its own height across the whole width.
 *
 * The cards are laid out at the size given them, which is what a card cannot fit its text into that
 * squeezes. Internal, not private, so a test can hold it to its rule on boxes of known sizes.
 */
@Composable
internal fun QuestionLayout(
    wideMinWidth: Dp,
    gap: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val policy = remember(wideMinWidth, gap) { QuestionLayoutPolicy(wideMinWidth, gap) }
    Layout(content = content, modifier = modifier, measurePolicy = policy)
}

private class QuestionLayoutPolicy(
    private val wideMinWidth: Dp,
    private val gap: Dp,
) : MeasurePolicy {
    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val (cardA, row, cardB) = children(measurables)
        val rowPlaced = row.measure(constraints.copy(minWidth = 0, minHeight = 0))

        if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
            // In a scroll, which the Play screen never is: stacked, each card its own height, as a Column.
            val free = Constraints(maxWidth = constraints.maxWidth)
            val a = cardA.measure(free)
            val b = cardB.measure(free)
            val width = maxOf(a.width, rowPlaced.width, b.width, constraints.minWidth)
            return layout(width, a.height + rowPlaced.height + b.height) {
                a.placeRelative(0, 0)
                rowPlaced.placeRelative(0, a.height)
                b.placeRelative(0, a.height + rowPlaced.height)
            }
        }

        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val cardsHeight = (height - rowPlaced.height).coerceAtLeast(0)
        return if (isWide(width, height)) {
            val (widthA, widthB) = widthsSideBySide(width)
            val a = cardA.measure(Constraints.fixed(widthA, cardsHeight))
            val b = cardB.measure(Constraints.fixed(widthB, cardsHeight))
            layout(width, height) {
                a.placeRelative(0, 0)
                b.placeRelative(width - b.width, 0)
                rowPlaced.placeRelative(0, cardsHeight)
            }
        } else {
            // Card A the smaller half of an odd height, as a Column shares it between two weights.
            val a = cardA.measure(Constraints.fixed(width, cardsHeight / 2))
            val b = cardB.measure(Constraints.fixed(width, cardsHeight - cardsHeight / 2))
            layout(width, height) {
                a.placeRelative(0, 0)
                rowPlaced.placeRelative(0, a.height)
                b.placeRelative(0, height - b.height)
            }
        }
    }

    private fun Density.isWide(
        width: Int,
        height: Int,
    ): Boolean = width > height && width >= wideMinWidth.roundToPx()

    /** Card A's and card B's widths side by side across [width], [gap] apart: A the smaller half of an odd one. */
    private fun Density.widthsSideBySide(width: Int): Pair<Int, Int> {
        val cards = (width - gap.roundToPx()).coerceAtLeast(0)
        return cards / 2 to cards - cards / 2
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
    ): Int = heightNeeded(measurables, width) { child, childWidth -> child.minIntrinsicHeight(childWidth) }

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
    ): Int = heightNeeded(measurables, width) { child, childWidth -> child.maxIntrinsicHeight(childWidth) }

    // As wide as the widest child, as the cards stacked need, which is how any narrow screen has them.
    override fun IntrinsicMeasureScope.minIntrinsicWidth(
        measurables: List<IntrinsicMeasurable>,
        height: Int,
    ): Int = children(measurables).toList().maxOf { it.minIntrinsicWidth(height) }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(
        measurables: List<IntrinsicMeasurable>,
        height: Int,
    ): Int = children(measurables).toList().maxOf { it.maxIntrinsicWidth(height) }

    /**
     * The least height at [width] at which no card is squeezed, [heightOf] giving a child's own at a
     * width. The arrangement hangs on the height as well, so this is the lesser of two: side by side,
     * when that needs less height than [width], at which the screen is still wider than tall; and
     * stacked otherwise, from the height at which the screen stops being wider than tall.
     */
    private fun IntrinsicMeasureScope.heightNeeded(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
        heightOf: (IntrinsicMeasurable, Int) -> Int,
    ): Int {
        val (cardA, row, cardB) = children(measurables)
        val rowHeight = heightOf(row, width)
        val stacked = rowHeight + 2 * maxOf(heightOf(cardA, width), heightOf(cardB, width))
        if (width == Constraints.Infinity || width < wideMinWidth.roundToPx()) return stacked

        val (widthA, widthB) = widthsSideBySide(width)
        val sideBySide = rowHeight + maxOf(heightOf(cardA, widthA), heightOf(cardB, widthB))
        return if (sideBySide < width) sideBySide else maxOf(stacked, width)
    }

    private fun <T> children(measurables: List<T>): Triple<T, T, T> {
        check(measurables.size == 3) { "card A, the row and card B, not ${measurables.size} children" }
        return Triple(measurables[0], measurables[1], measurables[2])
    }
}
