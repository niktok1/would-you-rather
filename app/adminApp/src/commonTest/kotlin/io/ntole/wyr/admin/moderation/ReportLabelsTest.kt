package io.ntole.wyr.admin.moderation

import io.ntole.wyr.admin.moderation.FakeModeration.Companion.REPORTED
import io.ntole.wyr.admin.moderation.FakeModeration.Companion.reported
import io.ntole.wyr.admin.tabLabelOf
import io.ntole.wyr.core.domain.moderation.ModerationRepository
import io.ntole.wyr.core.domain.moderation.ReportReason
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the Reports tab says of the reported questions. */
class ReportLabelsTest {
    @Test
    fun `the summary says what was read`() {
        val full = List(ModerationRepository.PAGE_SIZE) { REPORTED.first() }

        assertEquals("Not read yet.", reportsSummaryOf(null))
        assertEquals("Nothing reported.", reportsSummaryOf(emptyList()))
        assertEquals("1 reported question.", reportsSummaryOf(REPORTED.take(1)))
        assertEquals("2 reported questions, most reported first.", reportsSummaryOf(REPORTED))
        assertEquals(
            "The ${ModerationRepository.PAGE_SIZE} most reported; more may be, read in as these are dismissed.",
            reportsSummaryOf(full),
        )
    }

    @Test
    fun `a question's reports are counted by player and by reason most given first`() {
        assertEquals("Reported by 1 player", reportCountOf(1))
        assertEquals("Reported by 4 players", reportCountOf(4))
        assertEquals(
            "Offensive 3 · Real person 1",
            reasonsOf(mapOf(ReportReason.OFFENSIVE to 3, ReportReason.REAL_PERSON to 1)),
        )
        assertEquals("No reason counted.", reasonsOf(emptyMap()))
    }

    @Test
    fun `every reason has a name of its own`() {
        val names = ReportReason.entries.map(::reasonLabelOf)

        assertEquals(ReportReason.entries.size, names.toSet().size, "$names")
        assertEquals("A reason this build cannot name", reasonLabelOf(ReportReason.UNKNOWN))
    }

    @Test
    fun `the Reports tab counts what it lists once read and says when more may be reported`() {
        val full = List(ModerationRepository.PAGE_SIZE) { index -> reported("q5").copy(reportCount = index + 1) }

        assertEquals("Reports", tabLabelOf(Screen.REPORTS, ModerationState()))
        assertEquals("Reports (2)", tabLabelOf(Screen.REPORTS, ModerationState(reports = ReportList(REPORTED))))
        assertEquals(
            "Reports (${ModerationRepository.PAGE_SIZE}+)",
            tabLabelOf(Screen.REPORTS, ModerationState(reports = ReportList(full))),
        )
    }
}
