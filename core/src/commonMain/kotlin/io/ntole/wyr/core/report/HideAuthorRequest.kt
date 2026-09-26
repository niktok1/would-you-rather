package io.ntole.wyr.core.report

import kotlinx.serialization.Serializable

/**
 * Hide every question by the author of [questionId] from the player for good, those approved later
 * included (CLAUDE.md §8d, *Reports*), as [HideQuestionRequest] hides one. The author stays anonymous: a
 * player names them only by a question of theirs, and learns nothing of who they are, nor which other
 * questions are theirs. A question nobody wrote, a seed, hides only itself.
 *
 * Carries no player identity: the player is whoever the request's bearer token names. [questionId]
 * must not be blank or hold a control character, as for a vote. There is no response body.
 */
@Serializable
public data class HideAuthorRequest(
    public val questionId: String,
)
