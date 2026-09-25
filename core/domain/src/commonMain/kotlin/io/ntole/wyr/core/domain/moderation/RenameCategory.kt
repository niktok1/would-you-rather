package io.ntole.wyr.core.domain.moderation

import io.ntole.wyr.core.domain.category.Category

/**
 * Puts right both names of the category [id] (CLAUDE.md §8d, *Categories*); its id, and so what is
 * filed under it, never changes. Ensures no session, as [GetPendingSubmissions] explains.
 */
public class RenameCategory(
    private val moderation: ModerationRepository,
) {
    public suspend operator fun invoke(
        token: AdminToken,
        id: String,
        nameSr: String,
        nameEn: String,
    ): Category = moderation.renameCategory(token, id, nameSr, nameEn)
}
