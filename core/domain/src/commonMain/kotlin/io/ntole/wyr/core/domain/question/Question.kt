package io.ntole.wyr.core.domain.question

/**
 * A question as the app understands it, independent of the wire format.
 *
 * Deliberately not the same type as `QuestionDto`: domain code must never see a DTO, and the
 * mapping between the two lives in `:core:data` (CLAUDE.md §3).
 *
 * [answeredBefore] is true when the player has answered this question already and the feed has
 * looped back to it (CLAUDE.md §8d). It is for diagnostics such as the dev console: the
 * player-facing reveal does not show a previous pick.
 */
public data class Question(
    public val id: String,
    public val optionA: String,
    public val optionB: String,
    public val category: Category,
    public val answeredBefore: Boolean = false,
)

/**
 * Domain-side category.
 *
 * [OTHER] is where every category this build does not recognise lands. The wire enum's
 * `UNKNOWN` maps here, so a server-side category addition surfaces as a plain "uncategorised"
 * question instead of an error.
 */
public enum class Category {
    FOOD,
    LIFESTYLE,
    ETHICS,
    SUPERPOWERS,
    RANDOM,
    OTHER,
    ;

    public companion object {
        /**
         * Every category the feed can be filtered to, in declaration order: all but [OTHER], which
         * holds whatever this build cannot name, so there is nothing to ask the server for by it.
         */
        public val selectable: List<Category> = entries.filter { it != OTHER }
    }
}
