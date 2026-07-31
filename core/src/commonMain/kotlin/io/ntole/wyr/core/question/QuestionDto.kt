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
 * [category] must keep its default for unknown-value coercion to work — see [QuestionCategory].
 */
@Serializable
public data class QuestionDto(
    public val id: String,
    public val optionA: String,
    public val optionB: String,
    public val category: QuestionCategory = QuestionCategory.UNKNOWN,
)
