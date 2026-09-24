package io.ntole.wyr.core.domain.question

import kotlin.test.Test
import kotlin.test.assertFailsWith

class QuestionTest {
    @Test
    fun `a question filed under no category is refused`() {
        // A question is filed under at least one (CLAUDE.md §8d); one this build cannot name is OTHER.
        assertFailsWith<IllegalArgumentException> {
            Question(id = "q1", optionA = "Fly", optionB = "Turn invisible", categories = emptySet())
        }
    }
}
