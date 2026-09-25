package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.domain.reaction.QuestionReactions
import io.ntole.wyr.core.domain.reaction.Reaction
import io.ntole.wyr.core.reaction.ReactionResultDto
import io.ntole.wyr.core.reaction.Reaction as WireReaction

/**
 * Translation for reactions (CLAUDE.md §8d, *Reactions*). The only place the wire's `Reaction` and the
 * domain's are both in scope, under an alias for the wire's, since the two share a name.
 */
internal fun ReactionResultDto.toDomain(): QuestionReactions =
    QuestionReactions(
        questionId = questionId,
        likeCount = likeCount,
        dislikeCount = dislikeCount,
        myReaction = myReaction.toDomain(),
    )

/** The wire's reaction as the domain holds it: the enum is closed, so each has its one match. */
internal fun WireReaction.toDomain(): Reaction =
    when (this) {
        WireReaction.NONE -> Reaction.NONE
        WireReaction.LIKE -> Reaction.LIKE
        WireReaction.DISLIKE -> Reaction.DISLIKE
    }

/** The domain's reaction as a request sends it. */
internal fun Reaction.toWire(): WireReaction =
    when (this) {
        Reaction.NONE -> WireReaction.NONE
        Reaction.LIKE -> WireReaction.LIKE
        Reaction.DISLIKE -> WireReaction.DISLIKE
    }
