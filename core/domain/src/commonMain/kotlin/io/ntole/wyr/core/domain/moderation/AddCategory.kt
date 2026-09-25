package io.ntole.wyr.core.domain.moderation

import io.ntole.wyr.core.domain.category.Category

/**
 * Adds a category (CLAUDE.md §8d, *Categories*), under [id], or under the id the server makes from
 * the English name when there is none. Ensures no session, as [GetPendingSubmissions] explains.
 */
public class AddCategory(
    private val moderation: ModerationRepository,
) {
    public suspend operator fun invoke(
        token: AdminToken,
        id: String?,
        nameSr: String,
        nameEn: String,
    ): Category = moderation.addCategory(token, id, nameSr, nameEn)
}
