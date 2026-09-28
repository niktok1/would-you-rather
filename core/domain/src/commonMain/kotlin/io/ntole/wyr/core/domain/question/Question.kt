package io.ntole.wyr.core.domain.question

import io.ntole.wyr.core.domain.reaction.Reaction

/**
 * A question as the app understands it, independent of the wire format.
 *
 * Deliberately not the same type as `QuestionDto`: domain code must never see a DTO, and the
 * mapping between the two lives in `:core:data` (CLAUDE.md §3).
 *
 * [categories] is the id of every category the question is filed under (CLAUDE.md §8d), each once,
 * in the order of categories, as the server lists them: a
 * [io.ntole.wyr.core.domain.category.Category] of [io.ntole.wyr.core.domain.category.CategoryRepository]
 * names each. The server files every question under at least one; a payload without them, which no
 * server sends, reads as none.
 *
 * [likeCount] is how many players like the question and [dislikeCount] how many dislike it, this one
 * among them as [myReaction] says, as the server counted them when it served the question (CLAUDE.md
 * §8d, *Reactions*), answered or not: the counts are visible before answering. A queued question keeps
 * the numbers it was fetched with, so the answer to a reaction of the player's own, a
 * [io.ntole.wyr.core.domain.reaction.QuestionReactions], is newer than they are, and anyone else's
 * shows only when the feed next serves the question.
 *
 * [answeredBefore] is true when the player has answered the question already, in this cycle or one
 * before, as the feed said when it served it (CLAUDE.md §8d, *Endless feed*): the Play screen says so,
 * quietly (§8d, *The Play screen*).
 */
public data class Question(
    public val id: String,
    public val optionA: String,
    public val optionB: String,
    public val categories: Set<String>,
    public val likeCount: Int = 0,
    public val dislikeCount: Int = 0,
    public val myReaction: Reaction = Reaction.NONE,
    public val answeredBefore: Boolean = false,
)
