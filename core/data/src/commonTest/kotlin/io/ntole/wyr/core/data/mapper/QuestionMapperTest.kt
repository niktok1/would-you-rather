package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.network.WyrJson
import io.ntole.wyr.core.question.QuestionDto
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `a question keeps the id of every category it is filed under each once in the server's order`() {
        val cases =
            listOf(
                listOf("ETHICS", "SUPERPOWERS") to listOf("ETHICS", "SUPERPOWERS"),
                // The order of categories, as the server sent it: ABSURD is no longer RANDOM, and no
                // longer moved after FOOD by a declaration order.
                listOf("FOOD", "ABSURD") to listOf("FOOD", "ABSURD"),
                listOf("ABSURD", "FOOD") to listOf("ABSURD", "FOOD"),
                // A category added after this build is an id like the rest, kept to be named once read.
                listOf("ANIMALS", "ABSURD") to listOf("ANIMALS", "ABSURD"),
                // RANDOM went away with V6: an id like any other, and nothing stands in for it.
                listOf("RANDOM") to listOf("RANDOM"),
                listOf("FOOD", "FOOD") to listOf("FOOD"),
            )

        cases.forEach { (categories, expected) ->
            val dto = QuestionDto(id = "q1", optionA = "q1-a", optionB = "q1-b", categories = categories)

            // As a list, so the order is checked too.
            assertEquals(expected, dto.toDomain().categories.toList(), "$categories")
        }
    }

    @Test
    fun `a question sent without its categories is filed under none`() {
        // No server sends it: every question is filed under at least one. It still maps, not fails.
        val dto = WyrJson.decodeFromString<QuestionDto>("""{"id":"q1","optionA":"fly","optionB":"swim"}""")

        assertEquals(emptySet(), dto.toDomain().categories)
    }
}
