package io.ntole.wyr.core.data.category

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.domain.category.CategoryRules
import kotlin.test.Test
import kotlin.test.assertEquals

class CategoryLimitsTest {
    @Test
    fun `the client's category limits are the server's`() {
        // The domain cannot see :core, so it keeps its own copy of each number, checked here.
        assertEquals(WyrApi.Limits.MAX_CATEGORY_ID_LENGTH, CategoryRules.MAX_ID_LENGTH)
        assertEquals(WyrApi.Limits.MAX_CATEGORY_NAME_LENGTH, CategoryRules.MAX_NAME_LENGTH)
    }
}
