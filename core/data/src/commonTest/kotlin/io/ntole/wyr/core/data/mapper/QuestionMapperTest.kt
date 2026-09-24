package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.network.WyrJson
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
    fun `a question's likes survive the mapping`() {
        // Liked by others and not by the player too, so likedByMe cannot be read off the count.
        listOf(0 to false, 3 to true, 2 to false).forEach { (likeCount, likedByMe) ->
            val dto =
                QuestionDto(
                    id = "q1",
                    optionA = "q1-a",
                    optionB = "q1-b",
                    categories = listOf(QuestionCategory.FOOD),
                    likeCount = likeCount,
                    likedByMe = likedByMe,
                )

            val question = dto.toDomain()

            assertEquals(likeCount to likedByMe, question.likeCount to question.likedByMe)
        }
    }

    @Test
    fun `a question keeps every category it is filed under each once in declaration order`() {
        val cases =
            listOf(
                listOf(QuestionCategory.ETHICS, QuestionCategory.SUPERPOWERS) to
                    listOf(Category.ETHICS, Category.SUPERPOWERS),
                listOf(QuestionCategory.RANDOM, QuestionCategory.FOOD) to listOf(Category.FOOD, Category.RANDOM),
                // What a server with two categories this build predates sends it: each is OTHER, once.
                listOf(QuestionCategory.UNKNOWN, QuestionCategory.RANDOM, QuestionCategory.UNKNOWN) to
                    listOf(Category.RANDOM, Category.OTHER),
                listOf(QuestionCategory.UNKNOWN) to listOf(Category.OTHER),
                // No question is filed under nothing.
                emptyList<QuestionCategory>() to listOf(Category.OTHER),
            )

        cases.forEach { (categories, expected) ->
            val dto = QuestionDto(id = "q1", optionA = "q1-a", optionB = "q1-b", categories = categories)

            // As a list, so the order is checked too.
            assertEquals(expected, dto.toDomain().categories.toList(), "$categories")
        }
    }

    @Test
    fun `a question sent without its categories is filed under OTHER`() {
        val dto = WyrJson.decodeFromString<QuestionDto>("""{"id":"q1","optionA":"fly","optionB":"swim"}""")

        assertEquals(setOf(Category.OTHER), dto.toDomain().categories)
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
