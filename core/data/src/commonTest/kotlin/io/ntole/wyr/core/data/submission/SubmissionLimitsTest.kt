package io.ntole.wyr.core.data.submission

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.domain.submission.SubmissionRules
import kotlin.test.Test
import kotlin.test.assertEquals

class SubmissionLimitsTest {
    @Test
    fun `the client's submission limits are the server's`() {
        // The domain cannot see :core, so it keeps its own copy of each number, checked here.
        assertEquals(WyrApi.Limits.MAX_OPTION_LENGTH, SubmissionRules.MAX_OPTION_LENGTH)
        assertEquals(WyrApi.Limits.MAX_PENDING_SUBMISSIONS, SubmissionRules.MAX_PENDING_SUBMISSIONS)
        assertEquals(WyrApi.Limits.SUBMISSION_COST, SubmissionRules.SUBMISSION_COST)
    }
}
