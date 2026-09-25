package io.ntole.wyr.core.data.reaction

import io.ntole.wyr.core.data.mapper.toDomain
import io.ntole.wyr.core.data.mapper.toWire
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.session.withSessionRecovery
import io.ntole.wyr.core.domain.reaction.QuestionReactions
import io.ntole.wyr.core.domain.reaction.Reaction
import io.ntole.wyr.core.domain.reaction.ReactionRepository
import io.ntole.wyr.core.network.api.ReactionApi
import io.ntole.wyr.core.reaction.ReactionRequest

/**
 * Likes, dislikes and takes either back, recovering once from a session the server has stopped
 * accepting (see [withSessionRecovery]). A recovered session is a fresh guest, so the reaction is that
 * guest's, and so are the numbers that come back.
 *
 * The retry after a recovered session sends the same request again, the reaction included, as the new
 * guest. Any resend of a reaction is safe, since it sets the reaction rather than toggling it (CLAUDE.md
 * §8d, *Reactions*): one that lands twice leaves it as once. Nothing here resends after any other
 * failure, but a caller whose reaction was lost to `NETWORK` may simply ask for the same again.
 */
public class DefaultReactionRepository(
    private val api: ReactionApi,
    private val session: DefaultSessionRepository,
) : ReactionRepository {
    override suspend fun setReaction(
        questionId: String,
        reaction: Reaction,
    ): QuestionReactions {
        val request = ReactionRequest(questionId = questionId, reaction = reaction.toWire())

        return session.withSessionRecovery { api.setReaction(request) }.toDomain()
    }
}
