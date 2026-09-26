package io.ntole.wyr.admin.moderation

import io.ntole.wyr.core.domain.category.CategoryRepository
import io.ntole.wyr.core.domain.category.GetCategories
import io.ntole.wyr.core.domain.moderation.AddCategory
import io.ntole.wyr.core.domain.moderation.ApproveSubmission
import io.ntole.wyr.core.domain.moderation.DismissReports
import io.ntole.wyr.core.domain.moderation.GetPendingSubmissions
import io.ntole.wyr.core.domain.moderation.GetQuestions
import io.ntole.wyr.core.domain.moderation.GetReportedQuestions
import io.ntole.wyr.core.domain.moderation.ModerationRepository
import io.ntole.wyr.core.domain.moderation.RejectSubmission
import io.ntole.wyr.core.domain.moderation.RenameCategory
import io.ntole.wyr.core.domain.moderation.RestoreQuestion
import io.ntole.wyr.core.domain.moderation.RetireQuestion

/** The app's ViewModel over [moderation] and [categories], every use case on them, as the wiring builds it. */
fun moderationViewModelOver(
    moderation: ModerationRepository,
    categories: CategoryRepository,
): ModerationViewModel =
    ModerationViewModel(
        getPendingSubmissions = GetPendingSubmissions(moderation),
        approveSubmission = ApproveSubmission(moderation),
        rejectSubmission = RejectSubmission(moderation),
        getQuestions = GetQuestions(moderation),
        retireQuestion = RetireQuestion(moderation),
        restoreQuestion = RestoreQuestion(moderation),
        getReportedQuestions = GetReportedQuestions(moderation),
        dismissReports = DismissReports(moderation),
        getCategories = GetCategories(categories),
        addCategory = AddCategory(moderation),
        renameCategory = RenameCategory(moderation),
    )
