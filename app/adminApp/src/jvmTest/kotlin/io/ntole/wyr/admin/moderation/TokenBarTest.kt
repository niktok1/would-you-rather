package io.ntole.wyr.admin.moderation

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import io.ntole.wyr.admin.moderation.FakeModeration.Companion.TOKEN
import io.ntole.wyr.admin.theme.AdminTheme
import io.ntole.wyr.core.domain.category.GetCategories
import io.ntole.wyr.core.domain.moderation.ApproveSubmission
import io.ntole.wyr.core.domain.moderation.GetPendingSubmissions
import io.ntole.wyr.core.domain.moderation.GetQuestions
import io.ntole.wyr.core.domain.moderation.RejectSubmission
import io.ntole.wyr.core.domain.moderation.RestoreQuestion
import io.ntole.wyr.core.domain.moderation.RetireQuestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The token field driven by keys and clicks, as a moderator at the desktop window drives it. */
@OptIn(InternalComposeUiApi::class)
class TokenBarTest {
    private val moderation = FakeModeration()
    private val viewModel =
        ModerationViewModel(
            getPendingSubmissions = GetPendingSubmissions(moderation),
            approveSubmission = ApproveSubmission(moderation),
            rejectSubmission = RejectSubmission(moderation),
            getQuestions = GetQuestions(moderation),
            retireQuestion = RetireQuestion(moderation),
            restoreQuestion = RestoreQuestion(moderation),
            getCategories = GetCategories(FakeCategories()),
        )

    @Test
    fun `undo in the token field after Lock brings nothing back`() {
        val scene =
            ImageComposeScene(width = 800, height = 200, density = Density(1f)) {
                val state by viewModel.state.collectAsState()
                AdminTheme(darkTheme = false) { TokenBar(state, viewModel) }
            }
        try {
            scene.render()
            viewModel.setAdminToken(TOKEN)
            scene.clickTokenField()
            // A key command (a paste, say, or moving the caret) has the field's undo history keep what it
            // holds, the whole token.
            scene.press(Key.MoveHome)
            scene.press(Key.MoveEnd)

            viewModel.lock()
            scene.render()
            scene.clickTokenField()
            scene.press(Key.Z, shortcut = true)

            // Undo in a field that outlived the Lock gave the whole token back, and with it every request.
            assertEquals("", viewModel.state.value.adminToken.text)
            assertNull(viewModel.state.value.token)
        } finally {
            scene.close()
        }
    }

    private fun ImageComposeScene.clickTokenField() {
        // Inside the field, which starts at the bar's left edge and fills the row beside Lock.
        val inField = Offset(x = 100f, y = 35f)
        val primary = PointerButtons(isPrimaryPressed = true)
        sendPointerEvent(PointerEventType.Press, inField, buttons = primary, button = PointerButton.Primary)
        sendPointerEvent(PointerEventType.Release, inField, buttons = PointerButtons(), button = PointerButton.Primary)
        render()
    }

    /**
     * Presses [key] and lets the field answer it, with the host's shortcut modifier when [shortcut]:
     * Command on macOS, Control elsewhere.
     */
    private fun ImageComposeScene.press(
        key: Key,
        shortcut: Boolean = false,
    ) {
        val mac = System.getProperty("os.name").orEmpty().startsWith("Mac")
        listOf(KeyEventType.KeyDown, KeyEventType.KeyUp).forEach { type ->
            sendKeyEvent(
                KeyEvent(key, type, isMetaPressed = shortcut && mac, isCtrlPressed = shortcut && !mac),
            )
        }
        render()
    }
}
