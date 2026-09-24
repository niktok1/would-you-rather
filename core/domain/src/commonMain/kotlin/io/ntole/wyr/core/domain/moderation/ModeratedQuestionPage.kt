package io.ntole.wyr.core.domain.moderation

import kotlin.jvm.JvmInline

/**
 * One page of the list of every question, newest first. [next] is where the page after it starts,
 * to hand back to [ModerationRepository.questions] with the same filter, or null when this is the
 * last page.
 */
public data class ModeratedQuestionPage(
    public val questions: List<ModeratedQuestion>,
    public val next: QuestionCursor?,
)

/**
 * Where a page of the list of every question starts. Opaque: only a [ModeratedQuestionPage] gives
 * one, and it goes back as it came.
 */
@JvmInline
public value class QuestionCursor(
    public val value: String,
)
