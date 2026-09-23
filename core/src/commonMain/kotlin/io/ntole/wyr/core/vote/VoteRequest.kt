package io.ntole.wyr.core.vote

import kotlinx.serialization.Serializable

/**
 * Cast a vote on a question.
 *
 * Carries no player identity on purpose. The voter is established by the request's
 * `Authorization: Bearer <token>` header, not by the body, so this DTO does not change when
 * guest sessions are later upgraded to linked provider accounts.
 *
 * [attemptId] is a client-generated idempotency key (CLAUDE.md §8d, retry safety): one per answer
 * the player gives, sent again unchanged when that answer is retried. A repeat of the attempt the
 * server last recorded for this question is replayed and pays nothing; a new one is a fresh
 * answer. Non-blank and at most [io.ntole.wyr.core.api.WyrApi.Limits.MAX_ATTEMPT_ID_LENGTH]
 * characters. Neither id may hold a control character.
 */
@Serializable
public data class VoteRequest(
    public val questionId: String,
    public val choice: OptionSide,
    public val attemptId: String,
)
