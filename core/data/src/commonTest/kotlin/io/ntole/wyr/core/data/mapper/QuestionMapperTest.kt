package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionDto
import kotlin.test.Test
import kotlin.test.assertEquals

class QuestionMapperTest {
    @Test
    fun `whether the feed looped back to a question survives the mapping`() {
        listOf(true, false).forEach { answeredBefore ->
            val dto =
                QuestionDto(
                    id = "q1",
                    optionA = "q1-a",
                    optionB = "q1-b",
                    category = QuestionCategory.FOOD,
                    answeredBefore = answeredBefore,
                )

            assertEquals(answeredBefore, dto.toDomain().answeredBefore)
        }
    }
}
