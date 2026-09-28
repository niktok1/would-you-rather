package io.ntole.wyr.play

import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

/**
 * One of the Play screen's row's icon buttons ([icon]), and the [label] that stands right after its
 * icon, if any: a thumb and its count, or the question's menu, Share or Skip alone.
 */
internal class RowSlot(
    val icon: @Composable () -> Unit,
    val label: (@Composable () -> Unit)? = null,
)

/**
 * How the row's icons and gaps give way when it is tight: each icon's slot from [slotWidth] (a touch
 * target's, where there is room) down to [slotMinWidth]; a label stands [labelGap] past its icon, which
 * is [iconSize] wide in the middle of its slot; and the gaps between the start, the middle and the end
 * from [gap] down to none.
 */
internal class RowSpacing(
    val gap: Dp,
    val slotWidth: Dp,
    val slotMinWidth: Dp,
    val iconSize: Dp,
    val labelGap: Dp,
)

/**
 * [start] on the left, [middle] right after it and [end] on the right (CLAUDE.md §8d, *The Play
 * screen*): the Play screen's row between the cards, where [start] is the player's points, or how a
 * reaction failed, [middle] the thumbs with their counts and the question's menu, and [end] Share and
 * Skip. What does not fit is taken first from the space: the icons' slots narrow evenly from a touch
 * target's width towards [RowSpacing.slotMinWidth], and the gaps with them; then from [start], cut to
 * what is left, which only a reaction's failure, cut short on its two lines, ever needs. A label, a
 * thumb's count, is never cut.
 *
 * Each icon is laid out no wider than its slot, and square, with Material's minimum touch target left
 * to the pointer's own: Compose still takes a tap up to 48 around an icon button smaller than that, the
 * nearest one's where two such reaches meet, so a slot narrower than a touch target costs no tap.
 * Internal, not private, so a test can measure it.
 */
@Composable
internal fun PlayRow(
    spacing: RowSpacing,
    start: @Composable () -> Unit,
    middle: List<RowSlot>,
    end: List<RowSlot>,
    modifier: Modifier = Modifier,
) {
    val slots = middle + end
    // The start, then each icon and the label after it, in order: the order a screen reader goes through them.
    val contents: List<@Composable () -> Unit> =
        listOf(start) +
            slots.flatMap { slot ->
                val icon: @Composable () -> Unit = {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                        slot.icon()
                    }
                }
                listOfNotNull(icon, slot.label)
            }
    Layout(
        contents = contents,
        modifier = modifier,
        measurePolicy = PlayRowPolicy(spacing, labelled = slots.map { it.label != null }, middleCount = middle.size),
    )
}

private class PlayRowPolicy(
    private val spacing: RowSpacing,
    /** Whether each slot, the middle's and then the end's, has a label after its icon. */
    private val labelled: List<Boolean>,
    private val middleCount: Int,
) : MultiContentMeasurePolicy {
    /** The row's measurables split up: the start, each slot's icon, and each slot's label, or none. */
    private class Parts<T>(
        val start: T,
        val icons: List<T>,
        val labels: List<T?>,
    )

    private fun <T> split(measurables: List<List<T>>): Parts<T> {
        val icons = mutableListOf<T>()
        val labels = mutableListOf<T?>()
        var index = 1
        labelled.forEach { hasLabel ->
            icons += measurables[index++].single()
            labels += if (hasLabel) measurables[index++].single() else null
        }
        return Parts(measurables.first().single(), icons, labels)
    }

    /** How far a label stands from its slot's start, the slot [slot] wide: past its icon, in the slot's middle. */
    private fun Density.labelAt(slot: Float): Float = slot / 2 + spacing.iconSize.toPx() / 2 + spacing.labelGap.toPx()

    /**
     * The width slots [slot] wide take with labels [labelWidths] wide, or none: a labelled slot ends where
     * its label does.
     */
    private fun Density.slotsWidth(
        slot: Float,
        labelWidths: List<Int?>,
    ): Float = labelWidths.sumOf { label -> (if (label == null) slot else labelAt(slot) + label).toDouble() }.toFloat()

    /** How much wider the slots get for each pixel each slot does: a label covers half of its slot. */
    private val growth: Float = labelled.sumOf { if (it) 0.5 else 1.0 }.toFloat()

    override fun MeasureScope.measure(
        measurables: List<List<Measurable>>,
        constraints: Constraints,
    ): MeasureResult {
        val parts = split(measurables)
        val free = constraints.copy(minWidth = 0, minHeight = 0)
        val bounded = constraints.hasBoundedWidth
        val minSlot = spacing.slotMinWidth.toPx()

        // The labels as wide as they need: a count is never cut.
        val labels = parts.labels.map { it?.measure(free.copy(maxWidth = Constraints.Infinity)) }
        val labelWidths = labels.map { it?.width }
        val least = slotsWidth(minSlot, labelWidths)
        val startMax =
            if (bounded) {
                (constraints.maxWidth - least).roundToInt().coerceAtLeast(
                    0,
                )
            } else {
                Constraints.Infinity
            }
        val start = parts.start.measure(free.copy(maxWidth = startMax))

        // What is left once everything is at its narrowest widens the slots, up to a touch target's width
        // each, then the two gaps, and past that stands between the middle and the end.
        val room = if (bounded) constraints.maxWidth - start.width - least else Float.POSITIVE_INFINITY
        val slot = (minSlot + room.coerceAtLeast(0f) / growth).coerceAtMost(spacing.slotWidth.toPx())
        val gap =
            (
                (
                    room - (
                        slotsWidth(
                            slot,
                            labelWidths,
                        ) - least
                    )
                ).coerceAtLeast(0f) / 2
            ).coerceAtMost(spacing.gap.toPx())

        val slotPx = slot.roundToInt()
        val icons = parts.icons.map { it.measure(Constraints(maxWidth = slotPx, maxHeight = slotPx)) }
        val width =
            if (bounded) constraints.maxWidth else (start.width + slotsWidth(slot, labelWidths) + 2 * gap).roundToInt()
        val height =
            (
                listOf(start.height, constraints.minHeight) + icons.map { it.height } +
                    labels.mapNotNull { it?.height }
            ).max()

        return layout(width, height) {
            fun Placeable.placeCentred(x: Float) = placeRelative(x.roundToInt(), (height - this.height) / 2)

            // Slots [from] to [until], left to right from [x].
            fun placeSlots(
                from: Int,
                until: Int,
                x: Float,
            ) {
                var at = x
                (from until until).forEach { index ->
                    val icon = icons[index]
                    icon.placeCentred(at + (slot - icon.width) / 2)
                    val label = labels[index]
                    at +=
                        if (label == null) {
                            slot
                        } else {
                            label.placeCentred(at + labelAt(slot))
                            labelAt(slot) + label.width
                        }
                }
            }

            start.placeCentred(0f)
            placeSlots(0, middleCount, start.width + gap)
            placeSlots(middleCount, icons.size, width - slotsWidth(slot, labelWidths.drop(middleCount)))
        }
    }

    /** The least width that cuts nothing short but the start's words: every slot at its narrowest, no gap. */
    override fun IntrinsicMeasureScope.minIntrinsicWidth(
        measurables: List<List<IntrinsicMeasurable>>,
        height: Int,
    ): Int {
        val parts = split(measurables)
        val labels = parts.labels.map { it?.maxIntrinsicWidth(height) }
        return (parts.start.minIntrinsicWidth(height) + slotsWidth(spacing.slotMinWidth.toPx(), labels)).roundToInt()
    }

    /** As wide as it stands with room: every slot a touch target's width and both gaps whole. */
    override fun IntrinsicMeasureScope.maxIntrinsicWidth(
        measurables: List<List<IntrinsicMeasurable>>,
        height: Int,
    ): Int {
        val parts = split(measurables)
        val labels = parts.labels.map { it?.maxIntrinsicWidth(height) }
        val slots = slotsWidth(spacing.slotWidth.toPx(), labels)
        return (parts.start.maxIntrinsicWidth(height) + slots + 2 * spacing.gap.toPx()).roundToInt()
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(
        measurables: List<List<IntrinsicMeasurable>>,
        width: Int,
    ): Int = measurables.maxOf { it.single().minIntrinsicHeight(width) }

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(
        measurables: List<List<IntrinsicMeasurable>>,
        width: Int,
    ): Int = measurables.maxOf { it.single().maxIntrinsicHeight(width) }
}
