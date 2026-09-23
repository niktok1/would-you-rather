package io.ntole.wyr.core.data.vote

import io.ntole.wyr.core.data.mapper.toDomain
import io.ntole.wyr.core.data.mapper.toWire
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.data.session.withSessionRecovery
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.VoteOutcome
import io.ntole.wyr.core.domain.vote.VoteRepository
import io.ntole.wyr.core.network.api.VoteApi
import io.ntole.wyr.core.vote.VoteRequest
import kotlin.uuid.Uuid

/**
 * Casts votes, recovering once from a session the server has stopped accepting (see
 * [withSessionRecovery]).
 *
 * Each call is one answer, so it gets an attempt id of its own (CLAUDE.md §8d), and the retry
 * after a recovered session resends that same request.
 */
public class DefaultVoteRepository(
    private val api: VoteApi,
    private val session: DefaultSessionRepository,
) : VoteRepository {
    override suspend fun cast(
        questionId: String,
        side: Side,
    ): VoteOutcome {
        val request = VoteRequest(questionId = questionId, choice = side.toWire(), attemptId = Uuid.random().toString())

        return session.withSessionRecovery { api.cast(request) }.toDomain()
    }
}
