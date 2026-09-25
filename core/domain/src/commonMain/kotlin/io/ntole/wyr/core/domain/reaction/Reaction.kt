package io.ntole.wyr.core.domain.reaction

/**
 * What a player thinks of a question (CLAUDE.md §8d, *Reactions*): they like it, they dislike it, or
 * neither, [NONE]. A player holds one of the three per question.
 *
 * Deliberately not the wire's enum: domain code never sees a DTO (CLAUDE.md §3), and the mapping lives
 * in `:core:data`.
 */
public enum class Reaction {
    NONE,
    LIKE,
    DISLIKE,
}
