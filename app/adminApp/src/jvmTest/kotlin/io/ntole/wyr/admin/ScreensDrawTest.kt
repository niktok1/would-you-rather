package io.ntole.wyr.admin

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.ntole.wyr.admin.moderation.Action
import io.ntole.wyr.admin.moderation.CategoryDraft
import io.ntole.wyr.admin.moderation.CategoryList
import io.ntole.wyr.admin.moderation.DecisionDraft
import io.ntole.wyr.admin.moderation.Failure
import io.ntole.wyr.admin.moderation.FakeCategories
import io.ntole.wyr.admin.moderation.FakeModeration
import io.ntole.wyr.admin.moderation.ItemFailure
import io.ntole.wyr.admin.moderation.ModerationState
import io.ntole.wyr.admin.moderation.NoActions
import io.ntole.wyr.admin.moderation.Outcomes
import io.ntole.wyr.admin.moderation.PendingQueue
import io.ntole.wyr.admin.moderation.QuestionList
import io.ntole.wyr.admin.moderation.ReportList
import io.ntole.wyr.admin.moderation.Retiring
import io.ntole.wyr.admin.moderation.Running
import io.ntole.wyr.admin.moderation.Screen
import io.ntole.wyr.admin.moderation.SecretText
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.moderation.QuestionCursor
import io.ntole.wyr.core.domain.moderation.QuestionFilter
import io.ntole.wyr.core.domain.moderation.ReportReason
import io.ntole.wyr.core.domain.submission.SubmissionStatus
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
        Screen.entries.forEach { screen -> draw(WyrEnvironment.LOCAL, ModerationState(), screen) }
    }

    @Test
    fun `the app draws a queue with everything a queue can show`() {
        val failures =
            mapOf(
                "q1" to ItemFailure("\"Fly\" or \"Swim\"", Failure.Refused(DomainError.FORBIDDEN)),
                "gone" to ItemFailure("\"Cats\" or \"Dogs\"", Failure.Bug("IllegalStateException", "boom")),
            )
        val queue =
            ModerationState(
                adminToken = SecretText("typed"),
                pending =
                    PendingQueue(
                        submissions = FakeModeration.QUEUE,
                        failure = Failure.Refused(DomainError.RATE_LIMITED, 42.seconds, "too many requests"),
                        outcomes = Outcomes(failures, notice = "Approved \"Tea\" or \"Coffee\" under FOOD."),
                    ),
                // The categories read before, and a read of them since that failed.
                categories = CategoryList(FakeCategories.LISTED, Failure.Refused(DomainError.NETWORK)),
                drafts = mapOf("q1" to DecisionDraft(setOf("FOOD"), "not\none line")),
                running = Running(Action.APPROVE, "q2"),
            )

        WyrEnvironment.entries.forEach { environment -> draw(environment, queue, Screen.PENDING) }
    }

    @Test
    fun `the app draws a list with every status and everything a question can show`() {
        val reviewed = FakeModeration.LISTED.map { it.copy(reviewedAt = FakeModeration.RETIRED_AT) }
        val failures = mapOf("seed-1" to ItemFailure("\"Cats\" or \"Dogs\"", Failure.Refused(DomainError.WRONG_STATUS)))
        val list =
            ModerationState(
                adminToken = SecretText("typed"),
                questions =
                    QuestionList(
                        filter = QuestionFilter(setOf(SubmissionStatus.RETIRED), setOf("FOOD")),
                        questions = reviewed + FakeModeration.listed("q6", status = SubmissionStatus.OTHER),
                        next = QuestionCursor("6"),
                        failure = Failure.Refused(DomainError.UNKNOWN),
                        outcomes = Outcomes(failures, notice = "Restored \"Sea\" or \"Mountains\": served again."),
                    ),
                categories = CategoryList(FakeCategories.LISTED),
                drafts = mapOf("q1" to DecisionDraft(reason = "a duplicate")),
                running = Running(Action.RETIRE, "seed-1"),
            )

        draw(WyrEnvironment.DEV, list, Screen.QUESTIONS)
        // Retire waiting to be confirmed, its dialog over the list.
        draw(
            WyrEnvironment.PROD,
            list.copy(running = null, retiring = Retiring("seed-1", Screen.QUESTIONS)),
            Screen.QUESTIONS,
        )
    }

    @Test
    fun `the app draws the reported questions with everything a report can show`() {
        val retired = FakeModeration.reported("q3", mapOf(ReportReason.UNKNOWN to 2, ReportReason.OTHER to 1))
        val failures =
            mapOf(
                "q5" to ItemFailure("\"Early\" or \"Late\"", Failure.Refused(DomainError.QUESTION_NOT_FOUND)),
                "gone" to ItemFailure("\"Cats\" or \"Dogs\"", Failure.Refused(DomainError.NETWORK, detail = "lost")),
            )
        val reports =
            ModerationState(
                adminToken = SecretText("typed"),
                reports =
                    ReportList(
                        reports = FakeModeration.REPORTED + retired + FakeModeration.reported("q4", emptyMap()),
                        failure = Failure.Refused(DomainError.RATE_LIMITED, 12.seconds),
                        outcomes = Outcomes(failures, notice = "Dismissed the reports of \"Tea\" or \"Tea\"."),
                    ),
                categories = CategoryList(FakeCategories.LISTED),
                running = Running(Action.DISMISS_REPORTS, "q5"),
            )

        WyrEnvironment.entries.forEach { environment -> draw(environment, reports, Screen.REPORTS) }
        // Retire waiting to be confirmed from the reports, its dialog over them.
        draw(
            WyrEnvironment.PROD,
            reports.copy(running = null, retiring = Retiring("q5", Screen.REPORTS)),
            Screen.REPORTS,
        )
    }

    @Test
    fun `the app draws the categories with a category being added and one being renamed`() {
        val categories =
            ModerationState(
                adminToken = SecretText("typed"),
                categories =
                    CategoryList(
                        categories = FakeCategories.LISTED,
                        failure = Failure.Refused(DomainError.NETWORK),
                        adding = CategoryDraft(id = "fast food", nameSr = "Брза храна", nameEn = "Fast\nfood"),
                        renaming = CategoryDraft("FOOD", "Храна", ""),
                        addFailure = Failure.Refused(DomainError.CATEGORY_EXISTS, detail = "FOOD exists"),
                        renameFailure = Failure.Refused(DomainError.SERVER),
                        outcomes = Outcomes(notice = "Added SPORT: Спорт / Sport."),
                    ),
                running = Running(Action.RENAME_CATEGORY),
            )

        WyrEnvironment.entries.forEach { environment -> draw(environment, categories, Screen.CATEGORIES) }
    }

    private fun draw(
        environment: WyrEnvironment,
        state: ModerationState,
        screen: Screen,
    ) {
        listOf(false, true).forEach { dark ->
            val scene =
                ImageComposeScene(width = 1100, height = 1400, density = Density(1f)) {
                    ModerationApp(environment, state, NoActions, screen, onScreenChange = {}, darkTheme = dark)
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
