package io.ntole.wyr.admin

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.ntole.wyr.admin.moderation.Action
import io.ntole.wyr.admin.moderation.DecisionDraft
import io.ntole.wyr.admin.moderation.Failure
import io.ntole.wyr.admin.moderation.FakeModeration
import io.ntole.wyr.admin.moderation.ItemFailure
import io.ntole.wyr.admin.moderation.ModerationState
import io.ntole.wyr.admin.moderation.NoActions
import io.ntole.wyr.admin.moderation.PendingQueue
import io.ntole.wyr.admin.moderation.Running
import io.ntole.wyr.admin.moderation.SecretText
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.network.environment.WyrEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Every screen drawn off screen at a desktop window's size, in each theme, from states that fill it.
 * Compose measures and draws it all, so a layout that cannot be measured (a scroller inside
 * another, say) fails here rather than when the window opens.
 */
class ScreensDrawTest {
    @Test
    fun `the app draws before anything is read`() {
        draw(WyrEnvironment.LOCAL, ModerationState())
    }

    @Test
    fun `the app draws a queue with everything a queue can show`() {
        val queue =
            ModerationState(
                adminToken = SecretText("typed"),
                pending =
                    PendingQueue(
                        submissions = FakeModeration.QUEUE,
                        failure = Failure.Refused(DomainError.RATE_LIMITED, 42.seconds, "too many requests"),
                        failures =
                            mapOf(
                                "q1" to ItemFailure("\"Fly\" or \"Swim\"", Failure.Refused(DomainError.FORBIDDEN)),
                                "gone" to
                                    ItemFailure("\"Cats\" or \"Dogs\"", Failure.Bug("IllegalStateException", "boom")),
                            ),
                        notice = "Approved \"Tea\" or \"Coffee\" under FOOD.",
                    ),
                drafts = mapOf("q1" to DecisionDraft(setOf(Category.FOOD), "not\none line")),
                running = Running(Action.APPROVE, "q2"),
            )

        WyrEnvironment.entries.forEach { environment -> draw(environment, queue) }
    }

    private fun draw(
        environment: WyrEnvironment,
        state: ModerationState,
    ) {
        listOf(false, true).forEach { dark ->
            val scene =
                ImageComposeScene(width = 1100, height = 1400, density = Density(1f)) {
                    ModerationApp(environment, state, NoActions, darkTheme = dark)
                }
            try {
                val image = scene.render()
                assertEquals(1100, image.width)
            } finally {
                scene.close()
            }
        }
    }
}
