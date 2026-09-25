package io.ntole.wyr.core.domain.question

/**
 * A question as the app understands it, independent of the wire format.
 *
 * Deliberately not the same type as `QuestionDto`: domain code must never see a DTO, and the
 * mapping between the two lives in `:core:data` (CLAUDE.md §3).
 *
 * [categories] is every category the question is filed under (CLAUDE.md §8d), and never empty, since
 * a question is filed under at least one. Each one this build cannot name is [Category.OTHER], so a
 * question filed under none it can name is filed under [Category.OTHER] alone.
 *
 * [likeCount] is how many players like the question, this one included when [likedByMe], as the
 * server counted them when it served the question (CLAUDE.md §8d), answered or not: a like count is
 * visible before answering. A queued question keeps the numbers it was fetched with, so the answer
 * to a like of the player's own, a [io.ntole.wyr.core.domain.like.QuestionLikes], is newer than
 * they are, and anyone else's like shows only when the feed next serves the question.
 */
public data class Question(
    public val id: String,
    public val optionA: String,
    public val optionB: String,
    public val categories: Set<Category>,
    public val likeCount: Int = 0,
    public val likedByMe: Boolean = false,
) {
    init {
        require(categories.isNotEmpty()) { "question $id is filed under no category" }
    }
}

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
         * The same ones are what a question can be submitted under.
         */
        public val selectable: List<Category> = entries.filter { it != OTHER }
    }
}
