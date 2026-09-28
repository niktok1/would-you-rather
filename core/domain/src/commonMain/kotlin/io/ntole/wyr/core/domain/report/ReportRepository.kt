package io.ntole.wyr.core.domain.report

/**
 * Reports a question to the moderator, or hides it or its author from the session player, each for
 * good (CLAUDE.md §8d, *Reports*). Implemented in `:core:data`.
 *
 * Each of the three hides the question from the player: the feed never serves it to them again. Each
 * is safe to send again, a report replacing its reason and a hide changing nothing, so a call whose
 * answer was lost can simply be made again.
 *
 * Every one throws [io.ntole.wyr.core.domain.error.WyrException] on any failure, with
 * [io.ntole.wyr.core.domain.error.DomainError.QUESTION_NOT_FOUND] for a question no player is served.
 */
public interface ReportRepository {
    /** Reports the question [questionId] to the moderator for [reason], which hides it too. */
    public suspend fun report(
        questionId: String,
        reason: ReportReason,
    )

    /** Hides the question [questionId] from the player, without reporting it. */
    public suspend fun hideQuestion(questionId: String)

    /**
     * Hides every question by the author of the question [questionId] from the player, those approved
     * later included. The author stays anonymous. A question nobody wrote, a seed, hides only itself.
     */
    public suspend fun hideAuthor(questionId: String)
}
