package io.ntole.wyr.core.data.account

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.domain.account.AccountRules
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountLimitsTest {
    @Test
    fun `the client's account limits are the server's`() {
        // The domain cannot see :core, so it keeps its own copy of each number, checked here.
        assertEquals(WyrApi.Limits.MIN_USERNAME_LENGTH, AccountRules.MIN_USERNAME_LENGTH)
        assertEquals(WyrApi.Limits.MAX_USERNAME_LENGTH, AccountRules.MAX_USERNAME_LENGTH)
        assertEquals(WyrApi.Limits.MIN_PASSWORD_LENGTH, AccountRules.MIN_PASSWORD_LENGTH)
        assertEquals(WyrApi.Limits.MAX_PASSWORD_LENGTH, AccountRules.MAX_PASSWORD_LENGTH)
    }
}
