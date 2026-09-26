package io.ntole.wyr.core.author

import kotlinx.serialization.Serializable

/**
 * Block the author [authorId] names from submitting questions, and reject every submission of theirs
 * still pending, each for [reason] ([io.ntole.wyr.core.api.WyrApi.Paths.ADMIN_AUTHOR_BLOCKS], CLAUDE.md
 * §8d, *Moderation*). [authorId] is a question's
 * [io.ntole.wyr.core.question.AdminQuestionDto.authorId] or a submission's
 * [io.ntole.wyr.core.question.SubmissionDto.authorId], as the admin routes send it. [reason] is held
 * to a rejection's rules ([io.ntole.wyr.core.question.RejectSubmissionRequest]): each rejected author
 * sees it beside each question.
 */
@Serializable
public data class BlockAuthorRequest(
    public val authorId: String,
    public val reason: String,
)
