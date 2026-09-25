package io.ntole.wyr.admin.moderation

import io.ntole.wyr.admin.moderation.FakeModeration.Companion.LISTED
import io.ntole.wyr.admin.moderation.FakeModeration.Companion.RETIRED_AT
import io.ntole.wyr.admin.moderation.FakeModeration.Companion.listed
import io.ntole.wyr.admin.tabLabelOf
import io.ntole.wyr.core.domain.moderation.ModerationRepository
import io.ntole.wyr.core.domain.moderation.QuestionCursor
import io.ntole.wyr.core.domain.moderation.QuestionFilter
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.core.domain.vote.Tally
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/** How the list of every question words what it shows. */
class QuestionLabelsTest {
    @Test
    fun `every status has a name, the one this build cannot read included`() {
        assertEquals(
            listOf("Pending", "Approved", "Rejected", "Retired", "Unknown status"),
            SubmissionStatus.entries.map(::statusLabelOf),
        )
    }

    @Test
    fun `the filter says what it lists, none picked being every one`() {
        assertEquals("every status", statusFilterOf(QuestionFilter()))
        assertEquals("every category", categoryFilterOf(QuestionFilter(), FakeCategories.LISTED))

        val picked =
            QuestionFilter(
                setOf(SubmissionStatus.APPROVED, SubmissionStatus.RETIRED),
                setOf("ETHICS", "FOOD"),
            )
        assertEquals("Approved, Retired", statusFilterOf(picked))
        assertEquals("Етика, Храна", categoryFilterOf(picked, FakeCategories.LISTED))
    }

    @Test
    fun `the list says whether it was read, how many it holds and whether more follow`() {
        assertEquals("Not read at this filter yet.", listSummaryOf(QuestionList()))
        assertEquals("No question matches.", listSummaryOf(QuestionList(questions = emptyList())))
        assertEquals(
            "2 listed, newest first, and more to load.",
            listSummaryOf(QuestionList(questions = LISTED.take(2), next = QuestionCursor("2"))),
        )
        assertEquals("5 listed, newest first: all of them.", listSummaryOf(QuestionList(questions = LISTED)))
    }

    @Test
    fun `a question's votes read as each side's count and share`() {
        assertEquals("Votes: A 3 (75%), B 1 (25%)", tallyOf(listed("q")))
        assertEquals("No votes yet", tallyOf(listed("q").copy(tally = Tally(0, 0))))
        assertEquals("1 like", likesOf(1))
        assertEquals("0 likes", likesOf(0))
        assertEquals("1 dislike", dislikesOf(1))
        assertEquals("3 dislikes", dislikesOf(3))
    }

    @Test
    fun `a question's times are those that have happened`() {
        val now = listed("q").submittedAt + 3.days

        assertEquals(listOf("Submitted 3 d ago · 2026-09-21T14:21:40Z"), timesOf(listed("q"), now))
        assertEquals(
            listOf(
                "Seeded 3 d ago · 2026-09-21T14:21:40Z",
                "Reviewed 2026-09-21T14:28:20Z",
                "Retired 2026-09-21T14:28:20Z",
            ),
            timesOf(listed("s", status = SubmissionStatus.RETIRED, isSeed = true).copy(reviewedAt = RETIRED_AT), now),
        )
    }

    @Test
    fun `the retire warning names the question and says what stays`() {
        val warning = retireWarningOf(listed("seed-1", "Cats", "Dogs"))

        assertTrue(warning.startsWith("\"Cats\" or \"Dogs\" will be served to nobody"), warning)
        assertTrue("until it is restored" in warning && "all stay" in warning, warning)
        assertTrue(retireWarningOf(null).startsWith("This question will be served to nobody"))
    }

    @Test
    fun `a tab counts what it lists once it has read it`() {
        val read =
            ModerationState(
                pending = PendingQueue(submissions = FakeModeration.QUEUE),
                questions = QuestionList(questions = LISTED),
                categories = CategoryList(FakeCategories.LISTED),
            )

        assertEquals("Pending", tabLabelOf(Screen.PENDING, ModerationState()))
        assertEquals("All questions", tabLabelOf(Screen.QUESTIONS, ModerationState()))
        assertEquals("Categories", tabLabelOf(Screen.CATEGORIES, ModerationState()))
        assertEquals("Pending (2)", tabLabelOf(Screen.PENDING, read))
        assertEquals("All questions (5)", tabLabelOf(Screen.QUESTIONS, read))
        assertEquals("Categories (4)", tabLabelOf(Screen.CATEGORIES, read))
    }

    @Test
    fun `a queue as long as one read lists may hold more, and its tab says so`() {
        val full = List(ModerationRepository.PAGE_SIZE) { index -> FakeModeration.FIRST.copy(id = "q$index") }
        val state = ModerationState(pending = PendingQueue(submissions = full))

        assertEquals("Pending (${ModerationRepository.PAGE_SIZE}+)", tabLabelOf(Screen.PENDING, state))
    }
}
