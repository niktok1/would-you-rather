package io.ntole.wyr.core.data.like

import io.ntole.wyr.core.data.mapper.toDomain
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.session.withSessionRecovery
import io.ntole.wyr.core.domain.like.LikeRepository
import io.ntole.wyr.core.domain.like.QuestionLikes
import io.ntole.wyr.core.like.LikeRequest
import io.ntole.wyr.core.network.api.LikeApi

/**
 * Likes and unlikes questions, recovering once from a session the server has stopped accepting (see
 * [withSessionRecovery]). A recovered session is a fresh guest, so the like is that guest's, and so
 * are the numbers that come back.
 *
 * The retry after a recovered session sends the same request again, `liked` included, as the new
 * guest. Any resend of a like is safe, since it sets the like rather than toggling it (CLAUDE.md
 * §8d): one that lands twice leaves it as once. Nothing here resends after any other failure, but a
 * caller whose like was lost to `NETWORK` may simply ask for the same again.
 */
public class DefaultLikeRepository(
    private val api: LikeApi,
    private val session: DefaultSessionRepository,
) : LikeRepository {
    override suspend fun setLiked(
        questionId: String,
        liked: Boolean,
    ): QuestionLikes {
        val request = LikeRequest(questionId = questionId, liked = liked)

        return session.withSessionRecovery { api.setLiked(request) }.toDomain()
    }
}
