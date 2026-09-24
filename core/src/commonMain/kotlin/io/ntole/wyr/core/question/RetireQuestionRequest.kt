package io.ntole.wyr.core.question

import kotlinx.serialization.Serializable

/**
 * Retire an approved question (CLAUDE.md §8d, *Moderation*), with a POST to
 * [io.ntole.wyr.core.api.WyrApi.Paths.ADMIN_RETIREMENTS].
 *
 * [questionId] is the question's id, a seed's included. It must not be blank or hold a control
 * character, as for a [SkipRequest].
 */
@Serializable
public data class RetireQuestionRequest(
    public val questionId: String,
)
