package io.ntole.wyr.core.vote

import kotlinx.serialization.Serializable

/**
 * Cast a vote on a question.
 *
 * Carries no player identity on purpose. The voter is established by the request's
 * `Authorization: Bearer <token>` header, not by the body, so this DTO does not change when
 * guest sessions are later upgraded to linked provider accounts.
 */
@Serializable
public data class VoteRequest(
    public val questionId: String,
    public val choice: OptionSide,
)
