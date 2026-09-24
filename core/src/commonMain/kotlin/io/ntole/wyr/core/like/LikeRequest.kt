package io.ntole.wyr.core.like

import kotlinx.serialization.Serializable

/**
 * Like or unlike a question (CLAUDE.md §8d).
 *
 * Sets the like rather than toggling it: [liked] true means the player likes the question from now
 * on, false that they do not. Asking for what already holds changes nothing and pays nothing, so a
 * retry whose response was lost is harmless, and a like needs no attempt id as a vote does. [liked]
 * has no default, so a request that leaves it out asks for neither and is refused.
 *
 * Carries no player identity, for the reason [io.ntole.wyr.core.vote.VoteRequest] does not: the
 * player is whoever the request's bearer token names. [questionId] must not be blank or hold a
 * control character, and has no length limit, all as for a vote: one longer than any question's id
 * is only a question the server does not have.
 */
@Serializable
public data class LikeRequest(
    public val questionId: String,
    public val liked: Boolean,
)
