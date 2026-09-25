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
 * [categories] is every category the question is filed under (CLAUDE.md §8d, *Categories*): at least
 * one, each once, by id, in the server's order of categories, oldest first. A category is server data,
 * not an enum (CLAUDE.md §5): an id is a plain string, so one added after a client was built is only
 * an id that client has no name for, never one it fails to decode. The list defaults to empty, which
 * a client reads as a question filed under nothing it can name.
 *
 * [answeredBefore] is true when the requesting player has answered this question already and the
 * feed has looped back to it (CLAUDE.md §8d). No client reads it since the dev console, which
 * labelled a looped question, went; the player-facing reveal does not show a previous pick.
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
    public val categories: List<String> = emptyList(),
    public val answeredBefore: Boolean = false,
    public val likeCount: Int = 0,
    public val likedByMe: Boolean = false,
)
