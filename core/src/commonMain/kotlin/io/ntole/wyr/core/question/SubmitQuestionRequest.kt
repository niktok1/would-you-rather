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
 * [categories] are the author's pick, one or more (CLAUDE.md §8d), each the id of a category the
 * server has, in any order: the server files the question under each once, in its order of
 * categories, however often the request names it. None, which a missing list also reads as, is
 * refused as a malformed request, and so is an id no category has: a picker must have one picked
 * before it sends, and offers only the categories the server listed, so only a client bug, or a
 * client from before its category went away, can send either.
 */
@Serializable
public data class SubmitQuestionRequest(
    public val optionA: String,
    public val optionB: String,
    public val categories: List<String> = emptyList(),
)
