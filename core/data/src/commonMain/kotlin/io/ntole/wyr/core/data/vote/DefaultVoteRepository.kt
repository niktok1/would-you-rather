package io.ntole.wyr.core.data.vote

import io.ntole.wyr.core.data.mapper.toDomain
import io.ntole.wyr.core.data.mapper.toWire
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.session.withSessionRecovery
import io.ntole.wyr.core.domain.vote.AttemptId
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.VoteOutcome
import io.ntole.wyr.core.domain.vote.VoteRepository
import io.ntole.wyr.core.network.api.VoteApi
import io.ntole.wyr.core.vote.VoteRequest

/**
 * Casts votes, recovering once from a session the server has stopped accepting (see
 * [withSessionRecovery]).
 *
 * The retry after a recovered session resends the same request, the caller's attempt id included:
 * it is still the same answer, now sent as the new guest.
 */
public class DefaultVoteRepository(
    private val api: VoteApi,
    private val session: DefaultSessionRepository,
) : VoteRepository {
    override suspend fun cast(
        questionId: String,
        side: Side,
        attempt: AttemptId,
    ): VoteOutcome {
        val request = VoteRequest(questionId = questionId, choice = side.toWire(), attemptId = attempt.value)

        return session.withSessionRecovery { api.cast(request) }.toDomain()
    }
}
