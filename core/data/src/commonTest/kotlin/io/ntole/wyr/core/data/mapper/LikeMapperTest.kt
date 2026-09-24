package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.domain.like.QuestionLikes
import io.ntole.wyr.core.like.LikeResultDto
import kotlin.test.Test
import kotlin.test.assertEquals

class LikeMapperTest {
    @Test
    fun `where a question's likes stand survives the mapping`() {
        // Liked by others and not by the player too, so likedByMe cannot be read off the count.
        listOf(1 to true, 2 to false, 0 to false).forEach { (likeCount, likedByMe) ->
            val dto = LikeResultDto(questionId = "q1", likeCount = likeCount, likedByMe = likedByMe)

            assertEquals(QuestionLikes(questionId = "q1", likeCount = likeCount, likedByMe = likedByMe), dto.toDomain())
        }
    }
}
