package io.ntole.wyr.core.report

import kotlinx.serialization.Serializable

/**
 * Report a question to the moderator, for [reason] (CLAUDE.md §8d, *Reports*). A player reports a
 * question once: a report sent again, for the same reason or another, replaces the one before. The
 * report also hides the question from the player, as [HideQuestionRequest] does, whatever the
 * moderator makes of it.
 *
 * [reason] defaults to [ReportReason.UNKNOWN], as the wire enum rule asks (CLAUDE.md §5), and a
 * report that gives no real reason, UNKNOWN or none, is refused. Carries no player identity: the
 * player is whoever the request's bearer token names. [questionId] must not be blank or hold a
 * control character, as for a vote.
 *
 * There is no response body.
 */
@Serializable
public data class ReportRequest(
    public val questionId: String,
    public val reason: ReportReason = ReportReason.UNKNOWN,
)
