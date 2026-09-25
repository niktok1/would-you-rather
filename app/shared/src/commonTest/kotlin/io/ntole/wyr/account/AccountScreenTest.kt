package io.ntole.wyr.account

import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.Submission
import io.ntole.wyr.core.domain.submission.SubmissionStatus
import io.ntole.wyr.core.network.environment.WyrEnvironment
import io.ntole.wyr.language.EnglishStrings
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.SerbianCyrillicStrings
import io.ntole.wyr.language.SerbianLatinStrings
import io.ntole.wyr.language.stringsOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * What the Account screen says: where each of My questions stands, and which server it names, by the
 * environment the build was made for (CLAUDE.md §8e).
 */
class AccountScreenTest {
    @Test
    fun `each status reads in a word and a rejection with its reason`() {
        val english = EnglishStrings.accountScreens
        assertEquals("Pending", statusText(PENDING, english))
        assertEquals("Approved", statusText(PENDING.copy(status = SubmissionStatus.APPROVED), english))
        assertEquals("Retired", statusText(PENDING.copy(status = SubmissionStatus.RETIRED), english))
        assertEquals("Unknown", statusText(PENDING.copy(status = SubmissionStatus.OTHER), english))
        val rejected = PENDING.copy(status = SubmissionStatus.REJECTED, rejectionReason = "Too close to a seed")
        assertEquals("Rejected: Too close to a seed", statusText(rejected, english))
        assertEquals("Rejected", statusText(rejected.copy(rejectionReason = null), english))
    }

    /** The reason is the moderator's text, shown as they wrote it, in every language. */
    @Test
    fun `a rejection's reason is never transliterated`() {
        val rejected = PENDING.copy(status = SubmissionStatus.REJECTED, rejectionReason = "Исто као {0}")

        assertEquals("Одбијено: Исто као {0}", statusText(rejected, SerbianCyrillicStrings.accountScreens))
        assertEquals("Odbijeno: Исто као {0}", statusText(rejected, SerbianLatinStrings.accountScreens))
    }

    @Test
    fun `a dev build names the dev server and its URL in the language shown`() {
        assertEquals(
            "Server: Dev (https://wyr-server-dev.onrender.com)",
            serverLine(WyrEnvironment.DEV, EnglishStrings.accountScreens),
        )
        assertEquals(
            "Сервер: Dev (https://wyr-server-dev.onrender.com)",
            serverLine(WyrEnvironment.DEV, SerbianCyrillicStrings.accountScreens),
        )
    }

    @Test
    fun `a local build names the local server and the URL this platform reaches it at`() {
        assertEquals(
            "Server: Local (${WyrEnvironment.LOCAL.apiBaseUrl})",
            serverLine(WyrEnvironment.LOCAL, EnglishStrings.accountScreens),
        )
    }

    @Test
    fun `a prod build names no server`() {
        Language.entries.forEach { language ->
            assertNull(serverLine(WyrEnvironment.PROD, stringsOf(language).accountScreens), "$language")
        }
    }

    private companion object {
        val PENDING =
            Submission(
                id = "q1",
                optionA = "Fly",
                optionB = "Swim",
                categories = setOf(Category.SUPERPOWERS),
                status = SubmissionStatus.PENDING,
                rejectionReason = null,
                submittedAt = Instant.parse("2026-09-25T12:00:00Z"),
            )
    }
}
