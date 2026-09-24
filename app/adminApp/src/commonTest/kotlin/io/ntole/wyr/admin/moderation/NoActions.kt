package io.ntole.wyr.admin.moderation

import io.ntole.wyr.core.domain.question.Category

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

    override fun approve(questionId: String) = Unit

    override fun reject(questionId: String) = Unit
}
