package io.ntole.wyr.core.question

import kotlinx.serialization.Serializable

/**
 * One page of the moderator's list of every question
 * ([io.ntole.wyr.core.api.WyrApi.Paths.ADMIN_QUESTIONS]), newest first.
 *
 * [nextCursor] is where the next page starts: sent back as
 * [io.ntole.wyr.core.api.WyrApi.Query.CURSOR], with the same filters, it gets the questions after the
 * last one here. Null when there are none after it, so the list is done. It is opaque: a client sends
 * back the one it was given, as it was given, and never builds one.
 *
 * A page picks up where the last one ended, not at a count of questions skipped, so questions stored
 * meanwhile never push one already listed onto the next page: they are newer, and come before the
 * first page. A question whose status changes between two pages is listed once at most, where its
 * place in the order falls, if it matches the filter when that page is read.
 */
@Serializable
public data class AdminQuestionPageDto(
    public val questions: List<AdminQuestionDto>,
    public val nextCursor: String? = null,
)
