package io.ntole.wyr.core.question

import kotlinx.serialization.Serializable

/**
 * A single "would you rather" question.
 *
 * The two options are positional: the client reads side from which field it came out of,
 * so there is no separate side marker that could disagree with the field it sits in.
 *
 * [id] is a [String] to leave room for UUIDs.
 *
 * [categories] is every category the question is filed under (CLAUDE.md §8d): at least one, each
 * once, in [QuestionCategory] declaration order, as the server sends them. It must keep its
 * [QuestionCategoryListSerializer] and its empty default, the wire enum rule for a list (CLAUDE.md
 * §5): on a build older than a category, each name it does not know decodes as
 * [QuestionCategory.UNKNOWN], and a missing list as an empty one. A client reads an empty list as it
 * reads one of nothing but [QuestionCategory.UNKNOWN]: a question filed under nothing it can name.
 *
 * [answeredBefore] is true when the requesting player has answered this question already and the
 * feed has looped back to it. It exists so the dev console can label a looped question; the
 * player-facing reveal does not show a previous pick (CLAUDE.md §8d).
 *
 * [likeCount] is how many players like the question, the requesting one included when [likedByMe]
 * (CLAUDE.md §8d). Both are sent whether or not the player has answered it, since a like count is
 * visible before answering. The server reads them together, so they always agree. They default to
 * none, so a question from a server that sends no likes decodes as liked by nobody.
 */
@Serializable
public data class QuestionDto(
    public val id: String,
    public val optionA: String,
    public val optionB: String,
    @Serializable(with = QuestionCategoryListSerializer::class)
    public val categories: List<QuestionCategory> = emptyList(),
    public val answeredBefore: Boolean = false,
    public val likeCount: Int = 0,
    public val likedByMe: Boolean = false,
)
