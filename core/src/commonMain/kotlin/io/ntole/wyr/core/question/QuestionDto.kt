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
 *
 * [answeredBefore] is true when the requesting player has answered this question already and the
 * feed has looped back to it. It exists so the dev console can label a looped question; the
 * player-facing reveal does not show a previous pick (CLAUDE.md §8d).
 */
@Serializable
public data class QuestionDto(
    public val id: String,
    public val optionA: String,
    public val optionB: String,
    public val category: QuestionCategory = QuestionCategory.UNKNOWN,
    public val answeredBefore: Boolean = false,
)
