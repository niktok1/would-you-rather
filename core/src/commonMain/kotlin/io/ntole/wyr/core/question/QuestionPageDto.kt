package io.ntole.wyr.core.question

import kotlinx.serialization.Serializable

/**
 * One page of questions, for batch prefetch into the local cache.
 *
 * [nextCursor] is opaque to the client: pass it back verbatim to fetch the following page.
 * `null` means there is nothing more to fetch.
 */
@Serializable
public data class QuestionPageDto(
    public val questions: List<QuestionDto>,
    public val nextCursor: String? = null,
)
