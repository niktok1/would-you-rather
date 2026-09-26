package io.ntole.wyr.admin

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import kotlin.test.assertNotNull

// What a screen drawn off screen shows and does, read through its semantics, since there is no Compose
// UI test library in the tree. The game's tests have their own (`:app:shared`'s SceneSemantics), which
// this app may not depend on (CLAUDE.md §3).

/** Every node the scene holds, each button's text merged into it, from the top down. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun ImageComposeScene.nodes(): List<SemanticsNode> =
    semanticsOwners
        .flatMap { owner -> owner.getAllSemanticsNodes(mergingEnabled = true) }
        .sortedWith(compareBy({ it.positionInRoot.y }, { it.positionInRoot.x }))

/** Every text the scene lays out, from the top down, a button's included. */
internal fun ImageComposeScene.texts(): List<String> = nodes().flatMap(::textsOf)

/**
 * Taps the first node from the top showing [text], as a click or a screen reader would, and draws
 * again once what it changed has reached the scene.
 */
internal fun ImageComposeScene.tap(text: String) {
    val node =
        nodes().firstOrNull { node ->
            text in textsOf(node) && SemanticsActions.OnClick in node.config
        }
    val tap = assertNotNull(node?.config?.getOrNull(SemanticsActions.OnClick)?.action, "nothing to tap shows \"$text\"")
    tap()
    settle()
}

private fun textsOf(node: SemanticsNode): List<String> =
    node.config
        .getOrNull(SemanticsProperties.Text)
        .orEmpty()
        .map { it.text }

/** Draws the scene again once what the last action changed has reached it. */
internal fun ImageComposeScene.settle() {
    repeat(2) {
        Snapshot.sendApplyNotifications()
        render()
    }
}
