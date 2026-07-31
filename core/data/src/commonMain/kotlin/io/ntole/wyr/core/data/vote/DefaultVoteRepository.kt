package io.ntole.wyr.core.data.vote

import io.ntole.wyr.core.data.mapper.runApi
import io.ntole.wyr.core.data.mapper.toDomain
import io.ntole.wyr.core.data.mapper.toWire
import io.ntole.wyr.core.data.session.DefaultSessionRepository
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.domain.vote.Side
import io.ntole.wyr.core.domain.vote.VoteOutcome
import io.ntole.wyr.core.domain.vote.VoteRepository
import io.ntole.wyr.core.network.api.VoteApi
import io.ntole.wyr.core.vote.VoteRequest

/**
 * Casts votes, recovering once from a session the server has stopped accepting.
 *
 * Ktor's bearer provider already refreshes an expired access token transparently. This handles
 * the case it cannot: the *refresh* token itself is dead (server restarted with a new signing
 * key, row pruned, storage restored from an old backup). Without the retry, a player in that
 * state would see every vote fail forever with no way out but reinstalling.
 */
public class DefaultVoteRepository(
    private val api: VoteApi,
    private val session: DefaultSessionRepository,
) : VoteRepository {
    override suspend fun cast(
        questionId: String,
        side: Side,
    ): VoteOutcome {
        val request = VoteRequest(questionId = questionId, choice = side.toWire())

        return try {
            runApi { api.cast(request) }.toDomain()
        } catch (failure: WyrException) {
            if (failure.error != DomainError.UNAUTHORIZED) throw failure

            session.reset()
            runApi { api.cast(request) }.toDomain()
        }
    }
}
