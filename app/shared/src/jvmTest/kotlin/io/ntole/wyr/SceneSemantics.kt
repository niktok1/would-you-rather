package io.ntole.wyr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.toSize
import java.util.WeakHashMap
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// What a screen drawn off screen shows and does, read through its semantics as a screen reader
// reads them, since there is no Compose UI test library in the tree.

/** Every node the scene holds, each button's text and name merged into it, from the top down. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun ImageComposeScene.nodes(): List<SemanticsNode> =
    semanticsOwners
        .flatMap { owner -> owner.getAllSemanticsNodes(mergingEnabled = true) }
        .sortedWith(compareBy({ it.positionInRoot.y }, { it.positionInRoot.x }))

/** Every text the scene shows, from the top down. */
internal fun ImageComposeScene.texts(): List<String> = nodes().flatMap { it.texts }

/**
 * Every node the scene holds, each on its own, a button's text apart from the button, from the top
 * down: where each is laid out, not where it is clipped to.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun ImageComposeScene.everyNode(): List<SemanticsNode> =
    semanticsOwners
        .flatMap { owner -> owner.getAllSemanticsNodes(mergingEnabled = false) }
        .sortedWith(compareBy({ it.positionInRoot.y }, { it.positionInRoot.x }))

/**
 * Every text the scene lays out, each on its own, a field's label and the text under it included,
 * from the top down: where each is laid out, not where it is clipped to, so a screen that scrolls
 * shows all of its texts here.
 */
internal fun ImageComposeScene.everyText(): List<String> = everyNode().flatMap { it.texts }

/** Every name the scene gives a screen reader for what has no text, an icon's, from the top down. */
internal fun ImageComposeScene.descriptions(): List<String> = nodes().flatMap { it.descriptions }

internal val SemanticsNode.texts: List<String>
    get() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

internal val SemanticsNode.descriptions: List<String>
    get() = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

/**
 * That everything the scene lays out that shows or does something, each text, named icon, button,
 * field and ticked line, lies in a column [column] wide down the middle of the scene's [width], and
 * that something spans it: a screen's content held to `WyrDimens.contentMaxWidth` on a wide screen
 * (CLAUDE.md §8d, *Wide screens*).
 */
internal fun ImageComposeScene.assertInCentredColumn(
    width: Int,
    column: Int,
    what: String,
    // Whether something in it spans the column whole, which proves the column is not narrower.
    spanned: Boolean = true,
) {
    val left = (width - column) / 2f
    // Where each is laid out, scrolled out of sight or not: bounds in the root are clipped to the screen.
    val parts = everyNode().filter { it.showsOrDoes }.map { Rect(it.positionInRoot, it.size.toSize()) }
    assertTrue(parts.isNotEmpty(), "$what shows nothing")
    parts.forEach { part ->
        assertTrue(part.left >= left - 0.5f && part.right <= left + column + 0.5f, "$what: $part is out of the column")
    }
    if (spanned) assertEquals(column.toFloat(), parts.maxOf { it.width }, 0.5f, "$what: nothing spans the column")
}

private val SemanticsNode.showsOrDoes: Boolean
    get() =
        texts.isNotEmpty() ||
            descriptions.isNotEmpty() ||
            SemanticsActions.OnClick in config ||
            SemanticsActions.SetText in config

/** Taps the one node showing [text] or named [text], as a finger or a screen reader would, and draws again. */
internal fun ImageComposeScene.tap(text: String) {
    val node = nodes().singleOrNull { text in it.texts || text in it.descriptions }
    val tap = assertNotNull(node?.config?.getOrNull(SemanticsActions.OnClick)?.action, "nothing to tap shows \"$text\"")
    tap()
    settle()
}

/** Types [text] into the [index]th text field from the top, as a keyboard would, and draws again. */
internal fun ImageComposeScene.type(
    index: Int,
    text: String,
) {
    val fields = nodes().mapNotNull { it.config.getOrNull(SemanticsActions.SetText)?.action }
    val setText = assertNotNull(fields.getOrNull(index), "no text field $index of ${fields.size}")
    setText(AnnotatedString(text))
    settle()
}

/**
 * Draws the scene at [nanoTime] until what that frame changed shows, in its semantics and in what it
 * draws. Drawn once, a frame can miss what the desktop's snapshot manager, on a thread of its own,
 * does meanwhile: an animation then starts a frame late, or its value shows a frame late, and a test
 * fails now and then. The same frame drawn again changes nothing else.
 */
internal fun ImageComposeScene.renderAt(nanoTime: Long) {
    repeat(3) {
        Snapshot.sendApplyNotifications()
        render(nanoTime)
    }
}

/** Draws the scene again once what the last action changed has reached it, at the time [passTime] reached. */
internal fun ImageComposeScene.settle() {
    val now = timePassed[this] ?: 0L
    repeat(2) {
        Snapshot.sendApplyNotifications()
        render(now)
    }
}

/**
 * Moves the scene's clock on by [millis] from where it is, a frame every [FRAME_MILLIS] of it, so what
 * runs on the frame clock, an animation or a timeline, goes on as it would on a screen; [settle] draws
 * at the time reached from then on, never back at 0.
 */
internal fun ImageComposeScene.passTime(millis: Long) {
    val from = timePassed[this] ?: 0L
    var passed = 0L
    while (passed < millis) {
        passed = minOf(millis, passed + FRAME_MILLIS)
        renderAt(from + passed * NANOS_PER_MILLI)
    }
    timePassed[this] = from + millis * NANOS_PER_MILLI
}

/** Where [passTime] has moved each scene's clock, in nanoseconds; a scene it never moved is at 0. */
private val timePassed = WeakHashMap<ImageComposeScene, Long>()

private const val FRAME_MILLIS = 100L
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * The least height [content] needs at [width] for nothing in it to be squeezed, and the width it
 * needs for nothing to be cut short, at one pixel a dp.
 */
internal fun sizeNeeded(
    width: Int,
    height: Int,
    content: @Composable () -> Unit,
): Pair<Int, Int> {
    var needed = -1 to -1
    val scene =
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            Layout(content = content) { measurables, constraints ->
                val measurable = measurables.single()
                needed = measurable.maxIntrinsicWidth(constraints.maxHeight) to
                    measurable.minIntrinsicHeight(constraints.maxWidth)
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
        }
    try {
        scene.render()
    } finally {
        scene.close()
    }
    assertTrue(needed.first > 0 && needed.second > 0, "the content was never measured")
    return needed
}
