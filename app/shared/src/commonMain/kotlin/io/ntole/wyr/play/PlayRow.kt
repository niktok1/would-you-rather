package io.ntole.wyr.play

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/**
 * [start] on the left, [middle] right after it and [end] on the right (CLAUDE.md §8d, *The Play
 * screen*). What cannot fit is cut from [start]: [end] gets its whole width first, then [middle], and
 * [start] what they leave, with [gap] between each two.
 *
 * The Play screen's row between the cards, where [start] is the player's points, or how a reaction
 * failed, [middle] the thumbs and [end] Share and Skip. A plain `Row` would measure the points first,
 * and a failure's words, cut short only at `WyrDimens.playRowStartMaxWidth`, would push the thumbs'
 * counts or Skip off the row at 375 wide. Internal, not private, so a test can measure it.
 */
@Composable
internal fun PlayRow(
    gap: Dp,
    start: @Composable () -> Unit,
    middle: @Composable () -> Unit,
    end: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(contents = listOf(start, middle, end), modifier = modifier, measurePolicy = PlayRowPolicy(gap))
}

private class PlayRowPolicy(
    private val gap: Dp,
) : MultiContentMeasurePolicy {
    override fun MeasureScope.measure(
        measurables: List<List<Measurable>>,
        constraints: Constraints,
    ): MeasureResult {
        val (start, middle, end) = measurables.map { it.single() }
        val gap = gap.roundToPx()
        val free = constraints.copy(minWidth = 0, minHeight = 0)

        // What is left of the row's width once `taken` is: all of it, if the row has no bound.
        fun left(taken: Int): Int =
            if (constraints.hasBoundedWidth) (constraints.maxWidth - taken).coerceAtLeast(0) else Constraints.Infinity

        val endPlaced = end.measure(free.copy(maxWidth = left(0)))
        val middlePlaced = middle.measure(free.copy(maxWidth = left(endPlaced.width + 2 * gap)))
        val startPlaced = start.measure(free.copy(maxWidth = left(endPlaced.width + middlePlaced.width + 2 * gap)))
        val width =
            if (constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                startPlaced.width + middlePlaced.width + endPlaced.width + 2 * gap
            }
        val height = maxOf(startPlaced.height, middlePlaced.height, endPlaced.height, constraints.minHeight)

        // Right after start, whose width ensures it is never over end.
        val middleX = startPlaced.width + gap

        return layout(width, height) {
            startPlaced.placeRelative(0, (height - startPlaced.height) / 2)
            middlePlaced.placeRelative(middleX, (height - middlePlaced.height) / 2)
            endPlaced.placeRelative(width - endPlaced.width, (height - endPlaced.height) / 2)
        }
    }

    // As wide as the three side by side, and as high as the highest: none of them wraps in the row.
    override fun IntrinsicMeasureScope.maxIntrinsicWidth(
        measurables: List<List<IntrinsicMeasurable>>,
        height: Int,
    ): Int = measurables.sumOf { it.single().maxIntrinsicWidth(height) } + 2 * gap.roundToPx()

    override fun IntrinsicMeasureScope.minIntrinsicWidth(
        measurables: List<List<IntrinsicMeasurable>>,
        height: Int,
    ): Int = measurables.sumOf { it.single().minIntrinsicWidth(height) } + 2 * gap.roundToPx()

    override fun IntrinsicMeasureScope.minIntrinsicHeight(
        measurables: List<List<IntrinsicMeasurable>>,
        width: Int,
    ): Int = measurables.maxOf { it.single().minIntrinsicHeight(width) }

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(
        measurables: List<List<IntrinsicMeasurable>>,
        width: Int,
    ): Int = measurables.maxOf { it.single().maxIntrinsicHeight(width) }
}
