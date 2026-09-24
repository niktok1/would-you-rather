package io.ntole.wyr.core.question

import kotlinx.serialization.Serializable

/**
 * Submit a question of the player's own (CLAUDE.md §8d), with a POST to
 * [io.ntole.wyr.core.api.WyrApi.Paths.QUESTIONS].
 *
 * Carries no author, for the reason [io.ntole.wyr.core.vote.VoteRequest] carries no player: the
 * author is whoever the request's bearer token names.
 *
 * The server trims both options, as Kotlin's `trim()` does, and stores them trimmed. Trimmed, each
 * must be non-blank, at most [io.ntole.wyr.core.api.WyrApi.Limits.MAX_OPTION_LENGTH] long and free
 * of control characters and of U+2028 and U+2029, the line and paragraph separators (an option is
 * one line of text, and PostgreSQL refuses a NUL), and the two must differ ignoring case. Those are
 * the rules a player can break by what they type.
 *
 * [categories] are the author's pick, one or more (CLAUDE.md §8d), each a real one, in any order:
 * the server files the question under each once, in [QuestionCategory] declaration order, however
 * often the request names it. None, which a missing list also reads as, is refused as a malformed
 * request, and so is [QuestionCategory.UNKNOWN], which a name the server does not know decodes as
 * ([QuestionCategoryListSerializer]): a picker must have one picked before it sends, and offers
 * nothing it cannot name, so only a client bug can send either. The serializer and the empty
 * default are there for the wire enum rule (CLAUDE.md §5), not as values to send.
 */
@Serializable
public data class SubmitQuestionRequest(
    public val optionA: String,
    public val optionB: String,
    @Serializable(with = QuestionCategoryListSerializer::class)
    public val categories: List<QuestionCategory> = emptyList(),
)
