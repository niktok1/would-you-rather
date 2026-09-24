package io.ntole.wyr.admin.moderation

import io.ntole.wyr.admin.serverLineOf
import io.ntole.wyr.admin.windowTitleOf
import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.network.environment.WyrEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class LabelsTest {
    @Test
    fun `the window and the page name the server every request goes to`() {
        WyrEnvironment.entries.forEach { environment ->
            val title = windowTitleOf(environment)

            assertTrue(environment.displayName in title && environment.apiBaseUrl in title, title)
            assertEquals("Moderating the ${environment.displayName} server", serverLineOf(environment))
        }
    }

    @Test
    fun `a submission's age reads in the largest whole unit`() {
        val now = Instant.fromEpochSeconds(1_790_000_000L)

        assertEquals("just now", ageOf(now - 59.seconds, now))
        assertEquals("just now", ageOf(now + 5.seconds, now), "a clock behind the server's")
        assertEquals("1 min ago", ageOf(now - 1.minutes, now))
        assertEquals("59 min ago", ageOf(now - 59.minutes, now))
        assertEquals("3 h ago", ageOf(now - 3.hours - 59.minutes, now))
        assertEquals("2 d ago", ageOf(now - 2.days - 23.hours, now))
    }

    @Test
    fun `a time shows to the second, in UTC`() {
        assertEquals("2026-09-21T15:06:40Z", shownTime(Instant.fromEpochMilliseconds(1_790_003_200_999L)))
    }

    @Test
    fun `the token's status never shows the token`() {
        assertTrue("No token typed" in tokenStatusOf(" "))
        assertTrue("cannot be a token" in tokenStatusOf("s3cret token"))
        assertTrue("Held in memory only" in tokenStatusOf("s3cret-token"))
        assertFalse("s3cret" in tokenStatusOf("s3cret token"))
    }

    @Test
    fun `nothing picked approves under the author's categories`() {
        val authors = setOf(Category.FOOD, Category.OTHER)

        assertEquals("Approve under the author's categories: FOOD, OTHER", approvalOf(emptySet(), authors))
        assertEquals(
            "Approve under ETHICS, RANDOM, in place of the author's",
            approvalOf(setOf(Category.ETHICS, Category.RANDOM), authors),
        )
    }

    @Test
    fun `the queue says whether it was read and how many wait`() {
        assertEquals("Not read yet.", queueSummaryOf(null))
        assertEquals("Nothing waiting.", queueSummaryOf(emptyList()))
        assertEquals("1 waiting.", queueSummaryOf(listOf(FakeModeration.FIRST)))
        assertEquals("2 waiting, oldest first.", queueSummaryOf(FakeModeration.QUEUE))
    }
}
