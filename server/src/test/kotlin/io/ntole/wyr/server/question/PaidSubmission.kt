package io.ntole.wyr.server.question

import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.question.SubmitQuestionRequest
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.vote.Scoring

/**
 * [SubmissionStore.submit], by an author given the points it costs first (CLAUDE.md §8c), so their
 * total ends where it began: what a store test submits for, when it is not about the cost. Must run
 * inside a transaction.
 */
internal fun paidSubmission(
    authorId: String,
    request: SubmitQuestionRequest,
    now: Long = System.currentTimeMillis(),
): SubmissionDto {
    PlayerStore.addPoints(authorId, points = Scoring.SUBMISSION_COST)
    return SubmissionStore.submit(authorId, request, now)
}
