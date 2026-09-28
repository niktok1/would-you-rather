package io.ntole.wyr.services

import android.content.Intent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which intents are a tap on a decision's notification (CLAUDE.md §8a, *Push tokens*). */
class NotificationsTest {
    @Test
    fun `a decision's intent is a tap`() {
        assertTrue(isDecisionTap("submission_decided", Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Android 11 and before finish the activity at Back on Home; Recents gives the task's intent again. */
    @Test
    fun `the same intent relaunched from Recents is no tap`() {
        assertFalse(
            isDecisionTap(
                "submission_decided",
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY,
            ),
        )
    }

    @Test
    fun `a launcher's intent or another push's is no tap`() {
        assertFalse(isDecisionTap(null, 0))
        assertFalse(isDecisionTap("something_else", 0))
    }
}
