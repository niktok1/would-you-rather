package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.domain.reaction.QuestionReactions
import io.ntole.wyr.core.domain.reaction.Reaction
import io.ntole.wyr.core.reaction.ReactionResultDto
import kotlin.test.Test
import kotlin.test.assertEquals
import io.ntole.wyr.core.reaction.Reaction as WireReaction

class ReactionMapperTest {
    @Test
    fun `where a question's reactions stand survives the mapping`() {
        // Reacted to by others and not by the player too, so the player's own cannot be read off a count.
        listOf(
            Triple(1, 0, WireReaction.LIKE) to Reaction.LIKE,
            Triple(2, 3, WireReaction.DISLIKE) to Reaction.DISLIKE,
            Triple(2, 1, WireReaction.NONE) to Reaction.NONE,
        ).forEach { (sent, mine) ->
            val (likes, dislikes, myReaction) = sent
            val dto = ReactionResultDto("q1", likeCount = likes, dislikeCount = dislikes, myReaction = myReaction)

            assertEquals(
                QuestionReactions("q1", likeCount = likes, dislikeCount = dislikes, myReaction = mine),
                dto.toDomain(),
            )
        }
    }

    @Test
    fun `every reaction goes to the wire as itself and comes back as itself`() {
        Reaction.entries.forEach { reaction ->
            assertEquals(reaction.name, reaction.toWire().name)
            assertEquals(reaction, reaction.toWire().toDomain())
        }
    }
}
