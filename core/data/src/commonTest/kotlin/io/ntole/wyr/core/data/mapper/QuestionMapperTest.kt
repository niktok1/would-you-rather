package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.domain.reaction.Reaction
import io.ntole.wyr.core.network.WyrJson
import io.ntole.wyr.core.question.QuestionDto
import kotlin.test.Test
import kotlin.test.assertEquals
import io.ntole.wyr.core.reaction.Reaction as WireReaction

class QuestionMapperTest {
    @Test
    fun `a question's reactions survive the mapping`() {
        // Reacted to by others and not by the player too, so the player's own cannot be read off a count.
        listOf(
            Triple(0, 0, WireReaction.NONE) to Reaction.NONE,
            Triple(3, 1, WireReaction.LIKE) to Reaction.LIKE,
            Triple(2, 4, WireReaction.DISLIKE) to Reaction.DISLIKE,
            Triple(2, 4, WireReaction.NONE) to Reaction.NONE,
        ).forEach { (sent, mine) ->
            val (likes, dislikes, myReaction) = sent
            val dto =
                QuestionDto(
                    id = "q1",
                    optionA = "q1-a",
                    optionB = "q1-b",
                    categories = listOf("FOOD"),
                    likeCount = likes,
                    dislikeCount = dislikes,
                    myReaction = myReaction,
                )

            val question = dto.toDomain()

            assertEquals(
                Triple(likes, dislikes, mine),
                Triple(question.likeCount, question.dislikeCount, question.myReaction),
            )
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
    fun `whether the player answered a question before survives the mapping`() {
        listOf(false, true).forEach { answeredBefore ->
            val dto = QuestionDto(id = "q1", optionA = "q1-a", optionB = "q1-b", answeredBefore = answeredBefore)

            assertEquals(answeredBefore, dto.toDomain().answeredBefore)
        }
    }

    @Test
    fun `a question sent without its categories is filed under none`() {
        // No server sends it: every question is filed under at least one. It still maps, not fails.
        val dto = WyrJson.decodeFromString<QuestionDto>("""{"id":"q1","optionA":"fly","optionB":"swim"}""")

        assertEquals(emptySet(), dto.toDomain().categories)
    }
}
