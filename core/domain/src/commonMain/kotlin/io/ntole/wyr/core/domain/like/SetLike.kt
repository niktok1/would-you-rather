package io.ntole.wyr.core.domain.like

import io.ntole.wyr.core.domain.session.SessionRepository

/**
 * Likes or unlikes a question, guaranteeing a session exists first.
 *
 * A like is the session player's (CLAUDE.md §8d), so it needs a session just as voting does.
 * Ensuring it here, as `CastVote` and `SkipQuestion` do, sends the first like with a bearer instead
 * of having it refused and retried.
 *
 * [liked] is what to hold from now on, not a toggle: see [LikeRepository.setLiked].
 */
public class SetLike(
    private val likes: LikeRepository,
    private val session: SessionRepository,
) {
    public suspend operator fun invoke(
        questionId: String,
        liked: Boolean,
    ): QuestionLikes {
        session.ensure()
        return likes.setLiked(questionId, liked)
    }
}
