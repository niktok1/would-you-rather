package io.ntole.wyr.admin.moderation

import io.ntole.wyr.core.domain.question.Category
import io.ntole.wyr.core.domain.submission.SubmissionStatus

/** Buttons that do nothing, for drawing a screen from a state alone. */
object NoActions : ModerationActions {
    override fun setAdminToken(text: String) = Unit

    override fun lock() = Unit

    override fun loadPending() = Unit

    override fun toggleApprovalCategory(
        questionId: String,
        category: Category,
    ) = Unit

    override fun setReason(
        questionId: String,
        text: String,
    ) = Unit

    override fun approve(
        questionId: String,
        from: Screen,
    ) = Unit

    override fun reject(
        questionId: String,
        from: Screen,
    ) = Unit

    override fun toggleStatusFilter(status: SubmissionStatus) = Unit

    override fun toggleCategoryFilter(category: Category) = Unit

    override fun clearFilter() = Unit

    override fun loadQuestions() = Unit

    override fun loadMore() = Unit

    override fun askToRetire(questionId: String) = Unit

    override fun cancelRetire() = Unit

    override fun confirmRetire() = Unit

    override fun restore(questionId: String) = Unit
}
