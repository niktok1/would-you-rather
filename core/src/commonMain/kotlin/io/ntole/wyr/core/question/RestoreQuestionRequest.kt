package io.ntole.wyr.core.question

import kotlinx.serialization.Serializable

/**
 * Restore a retired question (CLAUDE.md §8d, *Moderation*), with a POST to
 * [io.ntole.wyr.core.api.WyrApi.Paths.ADMIN_RESTORATIONS]. [questionId] is as in a
 * [RetireQuestionRequest].
 */
@Serializable
public data class RestoreQuestionRequest(
    public val questionId: String,
)
