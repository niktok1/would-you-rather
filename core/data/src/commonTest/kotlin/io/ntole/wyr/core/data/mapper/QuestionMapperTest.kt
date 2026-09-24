package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuestionMapperTest {
    @Test
    fun `whether the feed looped back to a question survives the mapping`() {
        listOf(true, false).forEach { answeredBefore ->
            val dto =
                QuestionDto(
                    id = "q1",
                    optionA = "q1-a",
                    optionB = "q1-b",
                    categories = listOf(QuestionCategory.FOOD),
                    answeredBefore = answeredBefore,
                )

            assertEquals(answeredBefore, dto.toDomain().answeredBefore)
        }
    }

    @Test
    fun `a question plays under the first of its categories this build can name`() {
        val cases =
            listOf(
                listOf(QuestionCategory.ETHICS, QuestionCategory.SUPERPOWERS) to Category.ETHICS,
                listOf(QuestionCategory.UNKNOWN, QuestionCategory.RANDOM) to Category.RANDOM,
                listOf(QuestionCategory.UNKNOWN) to Category.OTHER,
                emptyList<QuestionCategory>() to Category.OTHER,
            )

        cases.forEach { (categories, expected) ->
            val dto = QuestionDto(id = "q1", optionA = "q1-a", optionB = "q1-b", categories = categories)

            assertEquals(expected, dto.toDomain().category, "$categories")
        }
    }

    @Test
    fun `every wire category maps to its domain namesake and UNKNOWN to OTHER`() {
        QuestionCategory.entries.forEach { wire ->
            val expected = if (wire == QuestionCategory.UNKNOWN) Category.OTHER else Category.valueOf(wire.name)

            assertEquals(expected, wire.toDomain(), "$wire")
        }
    }

    @Test
    fun `every category the server knows goes back on the wire as itself`() {
        // What a category filter sends is what the feed serves the category as.
        QuestionCategory.entries.filter { it != QuestionCategory.UNKNOWN }.forEach { wire ->
            assertEquals(wire, wire.toDomain().toWireOrNull(), "$wire")
        }
    }

    @Test
    fun `the categories a feed can be filtered to are every one the server knows`() {
        assertEquals(
            QuestionCategory.entries.filter { it != QuestionCategory.UNKNOWN },
            Category.selectable.map { it.toWireOrNull() },
        )
    }

    @Test
    fun `OTHER has no wire category to ask for`() {
        // Never UNKNOWN: that is a sentinel for what the client cannot read, not a filter.
        assertNull(Category.OTHER.toWireOrNull())
    }
}
