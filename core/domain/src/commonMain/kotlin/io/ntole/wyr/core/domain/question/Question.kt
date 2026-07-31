package io.ntole.wyr.core.domain.question

/**
 * A question as the app understands it, independent of the wire format.
 *
 * Deliberately not the same type as `QuestionDto`: domain code must never see a DTO, and the
 * mapping between the two lives in `:core:data` (CLAUDE.md §3).
 */
public data class Question(
    public val id: String,
    public val optionA: String,
    public val optionB: String,
    public val category: Category,
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
}
