package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.network.WyrJson
import io.ntole.wyr.core.question.QuestionDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuestionMapperTest {
    @Test
    fun `a question's likes survive the mapping`() {
        // Liked by others and not by the player too, so likedByMe cannot be read off the count.
        listOf(0 to false, 3 to true, 2 to false).forEach { (likeCount, likedByMe) ->
            val dto =
                QuestionDto(
                    id = "q1",
                    optionA = "q1-a",
                    optionB = "q1-b",
                    categories = listOf("FOOD"),
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
                listOf("ETHICS", "SUPERPOWERS") to listOf(Category.ETHICS, Category.SUPERPOWERS),
                listOf("ABSURD", "FOOD") to listOf(Category.FOOD, Category.RANDOM),
                // What a server with two categories this build predates sends it: each is OTHER, once.
                listOf("ANIMALS", "ABSURD", "TRAVEL") to listOf(Category.RANDOM, Category.OTHER),
                listOf("ANIMALS") to listOf(Category.OTHER),
                // RANDOM went away with V6: it is an id like any other this build cannot name.
                listOf("RANDOM") to listOf(Category.OTHER),
                // No question is filed under nothing.
                emptyList<String>() to listOf(Category.OTHER),
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
    fun `each of the server's first categories maps to its domain namesake and ABSURD to RANDOM`() {
        val expected =
            mapOf(
                "FOOD" to Category.FOOD,
                "LIFESTYLE" to Category.LIFESTYLE,
                "ETHICS" to Category.ETHICS,
                "SUPERPOWERS" to Category.SUPERPOWERS,
                "ABSURD" to Category.RANDOM,
            )

        expected.forEach { (id, category) -> assertEquals(category, id.toDomainCategory(), id) }
    }

    @Test
    fun `every category the server knows goes back on the wire as itself`() {
        // What a category filter sends is what the feed serves the category as.
        FIRST_CATEGORIES.forEach { id -> assertEquals(id, id.toDomainCategory().toWireOrNull(), id) }
    }

    @Test
    fun `the categories a feed can be filtered to are the server's first ones`() {
        assertEquals(FIRST_CATEGORIES, Category.selectable.map { it.toWireOrNull() })
    }

    @Test
    fun `OTHER has no wire category to ask for`() {
        // It stands for what the client cannot name, not a filter.
        assertNull(Category.OTHER.toWireOrNull())
    }

    private companion object {
        /** The ids V6 gave the server's first categories, oldest first. */
        val FIRST_CATEGORIES = listOf("FOOD", "LIFESTYLE", "ETHICS", "SUPERPOWERS", "ABSURD")
    }
}
