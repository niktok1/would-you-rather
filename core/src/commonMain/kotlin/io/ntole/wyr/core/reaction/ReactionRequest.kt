package io.ntole.wyr.core.reaction

import kotlinx.serialization.Serializable

/**
 * Like a question, dislike it, or take either back (CLAUDE.md §8d, *Reactions*).
 *
 * Sets the player's reaction rather than toggling it: [reaction] is what they hold from now on,
 * [Reaction.NONE] for neither. Asking for what already holds changes nothing and pays nothing, so a
 * retry whose response was lost is harmless, and a reaction needs no attempt id as a vote does.
 * [reaction] has no default, so a request that leaves it out asks for nothing and is refused.
 *
 * Carries no player identity, for the reason [io.ntole.wyr.core.vote.VoteRequest] does not: the
 * player is whoever the request's bearer token names. [questionId] must not be blank or hold a
 * control character, and has no length limit, all as for a vote: one longer than any question's id
 * is only a question the server does not have.
 */
@Serializable
public data class ReactionRequest(
    public val questionId: String,
    public val reaction: Reaction,
)
