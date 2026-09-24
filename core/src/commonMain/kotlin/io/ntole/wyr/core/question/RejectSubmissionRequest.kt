package io.ntole.wyr.core.question

import kotlinx.serialization.Serializable

/**
 * Reject a pending submission (CLAUDE.md §8d, *Moderation*), with a POST to
 * [io.ntole.wyr.core.api.WyrApi.Paths.ADMIN_REJECTIONS].
 *
 * [questionId] is as in an [ApproveSubmissionRequest].
 *
 * [reason] is the short reason the author sees ([SubmissionDto.rejectionReason]). The server trims
 * it, as Kotlin's `trim()` does, and stores it trimmed. Trimmed, it must be non-blank, at most
 * [io.ntole.wyr.core.api.WyrApi.Limits.MAX_REJECTION_REASON_LENGTH] long, and one line, free of
 * control characters and of U+2028 and U+2029, as a submitted option must be. Unlike an option, a
 * reason that breaks a rule is refused as a malformed request, 400
 * [io.ntole.wyr.core.error.ErrorCode.VALIDATION_FAILED]: the moderator's own client checks it
 * against the same rules and limit before it lets them send it, so only a client bug sends one.
 */
@Serializable
public data class RejectSubmissionRequest(
    public val questionId: String,
    public val reason: String,
)
