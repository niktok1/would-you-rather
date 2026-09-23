package io.ntole.wyr.core.question

import kotlinx.serialization.Serializable

/**
 * Skip a question for the rest of the player's current cycle (CLAUDE.md §8d).
 *
 * Carries no player identity, for the reason [io.ntole.wyr.core.vote.VoteRequest] does not: the
 * player is whoever the request's bearer token names. [questionId] must not be blank or hold a
 * control character, and has no length limit, all as for a vote: one longer than any question's id
 * is only a question the server does not have.
 *
 * There is no response body. A skip pays nothing and reveals nothing, so the client has nothing to
 * show for it and only moves on.
 */
@Serializable
public data class SkipRequest(
    public val questionId: String,
)
