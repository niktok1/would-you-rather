package io.ntole.wyr.admin.moderation

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.ntole.wyr.admin.ModerationApp
import io.ntole.wyr.admin.moderation.FakeModeration.Companion.TOKEN
import io.ntole.wyr.admin.settle
import io.ntole.wyr.admin.tap
import io.ntole.wyr.admin.texts
import io.ntole.wyr.core.network.environment.WyrEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The app's buttons and chips tapped as a moderator taps them, over the ViewModel and a scripted repository. */
@OptIn(ExperimentalCoroutinesApi::class)
class ModerationTapsTest {
    private val dispatcher = StandardTestDispatcher()
    private val moderation = FakeModeration()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a ready reason tapped fills the reason and Reject sends it`() =
        onScreen(Screen.PENDING) { viewModel, scene ->
            scene.tap("Load pending")
            advance(scene)

            scene.tap(READY_REASONS[1])
            assertEquals(
                READY_REASONS[1],
                viewModel.state.value
                    .draftOf("q1")
                    .reason,
                "the first card's",
            )
            scene.tap("Reject")
            advance(scene)

            assertEquals(listOf("pending", "reject q1 ${READY_REASONS[1]}", "pending"), moderation.calls)
        }

    @Test
    fun `Block author asks in a dialog and a ready reason tapped there is the one sent`() =
        onScreen(Screen.REPORTS) { viewModel, scene ->
            scene.tap("Load reports")
            advance(scene)

            // The first reported question is a player's: the seed after it has no author to block.
            scene.tap("Block author...")
            assertEquals(BlockDraft("author-of-q5", "q5", Screen.REPORTS), viewModel.state.value.blocking)
            scene.tap(READY_REASONS[0])
            scene.tap("Block")
            advance(scene)

            assertEquals(listOf("reports", "blockAuthor author-of-q5 ${READY_REASONS[0]}", "reports"), moderation.calls)
            // Where they stand now, and the one way to change it.
            val shown = scene.texts()
            assertTrue("Author author-o · blocked" in shown, "$shown")
            assertTrue("Unblock author" in shown, "$shown")
            assertFalse("Block author..." in shown, "$shown")
        }

    /** The app on [screen], the token typed, drawn off screen at a desktop window's size. */
    private fun onScreen(
        screen: Screen,
        test: (ModerationViewModel, ImageComposeScene) -> Unit,
    ) {
        val viewModel = moderationViewModelOver(moderation, FakeCategories())
        viewModel.setAdminToken(TOKEN)
        val scene =
            ImageComposeScene(width = 1100, height = 2400, density = Density(1f)) {
                val state by viewModel.state.collectAsState()
                ModerationApp(WyrEnvironment.LOCAL, state, viewModel, screen, onScreenChange = {}, darkTheme = false)
            }
        try {
            scene.settle()
            test(viewModel, scene)
        } finally {
            scene.close()
        }
    }

    /** Lets the ViewModel's work run, and the scene draw what it did. */
    private fun advance(scene: ImageComposeScene) {
        dispatcher.scheduler.advanceUntilIdle()
        scene.settle()
    }
}
