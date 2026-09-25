package io.ntole.wyr.core.domain.moderation

import io.ntole.wyr.core.domain.submission.SubmissionStatus

/**
 * Which questions the list of every question holds: those at any of [statuses] and filed under any
 * of [categories], by id, either empty for every one.
 *
 * [statuses] may not hold [SubmissionStatus.OTHER], which stands for what this build cannot name, so
 * there is nothing to ask the server for by it: [ModerationRepository.questions] refuses such a
 * filter before sending anything.
 */
public data class QuestionFilter(
    public val statuses: Set<SubmissionStatus> = emptySet(),
    public val categories: Set<String> = emptySet(),
)
