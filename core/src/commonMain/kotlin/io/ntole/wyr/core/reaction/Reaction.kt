package io.ntole.wyr.core.reaction

import kotlinx.serialization.Serializable

/**
 * What a player thinks of a question (CLAUDE.md §8d, *Reactions*): they like it, they dislike it, or
 * neither, [NONE]. A player holds one of the three per question, so liking a question they dislike
 * takes the dislike back.
 *
 * Deliberately closed, as [io.ntole.wyr.core.vote.OptionSide] is (CLAUDE.md §5): this enum gets no
 * `UNKNOWN` member. A thumbs up, a thumbs down or neither is the whole of what it says, so there is no
 * future value to degrade to. Another kind of reaction would be a field of its own, never a member
 * here.
 */
@Serializable
public enum class Reaction {
    NONE,
    LIKE,
    DISLIKE,
}
