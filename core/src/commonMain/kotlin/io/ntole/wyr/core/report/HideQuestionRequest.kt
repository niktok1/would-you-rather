package io.ntole.wyr.core.report

import kotlinx.serialization.Serializable

/**
 * Hide a question from the player for good, without reporting it (CLAUDE.md §8d, *Reports*): the feed
 * never serves it to them again, in this cycle or any after, and it is not due for them. Hiding it again
 * changes nothing. Nothing unhides one, for now.
 *
 * Carries no player identity: the player is whoever the request's bearer token names. [questionId]
 * must not be blank or hold a control character, as for a vote. There is no response body.
 */
@Serializable
public data class HideQuestionRequest(
    public val questionId: String,
)
