package io.ntole.wyr.core.domain.moderation

/**
 * Lists every question, seeds included, newest first, a page at a time (CLAUDE.md §8d,
 * *Moderation*): the first page for no cursor, then each [ModeratedQuestionPage.next] in turn until
 * one is null. Ensures no session, as [GetPendingSubmissions] explains.
 */
public class GetQuestions(
    private val moderation: ModerationRepository,
) {
    /** @throws IllegalArgumentException as [ModerationRepository.questions] does, having sent nothing. */
    public suspend operator fun invoke(
        token: AdminToken,
        filter: QuestionFilter = QuestionFilter(),
        after: QuestionCursor? = null,
    ): ModeratedQuestionPage = moderation.questions(token, filter, after)
}
