package io.ntole.wyr.dev

import io.ntole.wyr.core.domain.question.Category
import kotlin.test.Test
import kotlin.test.assertEquals

class DevConsoleScreenTest {
    @Test
    fun `a question shows every category it is filed under`() {
        assertEquals(
            "ETHICS, SUPERPOWERS, OTHER",
            namesOf(setOf(Category.ETHICS, Category.SUPERPOWERS, Category.OTHER)),
        )
    }
}
