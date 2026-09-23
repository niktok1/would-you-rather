package io.ntole.wyr.core.question

import kotlinx.serialization.Serializable

/**
 * The next batch of questions for the requesting player, for prefetch into the local cache.
 *
 * It carries no cursor: the server knows what the player has answered, so the next request simply
 * gets the next batch. A batch never lists a question twice, and it is never empty while the pool
 * (in the requested category, if any) is not — the feed loops back to answered questions rather
 * than run out (CLAUDE.md §8d).
 */
@Serializable
public data class QuestionPageDto(
    public val questions: List<QuestionDto>,
)
