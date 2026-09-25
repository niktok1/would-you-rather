package io.ntole.wyr.core.category

import kotlinx.serialization.Serializable

/**
 * Put right a category's names (CLAUDE.md §8d, *Categories*), with a POST to
 * [io.ntole.wyr.core.api.WyrApi.Paths.ADMIN_CATEGORY_RENAMES], answered with its [CategoryDto] as it
 * now stands. Both names are given, each as in a [CreateCategoryRequest]; [id] names the category and
 * never changes, so every question filed under it stays so.
 *
 * An id no category has is 404 [io.ntole.wyr.core.error.ErrorCode.CATEGORY_NOT_FOUND]; a name the
 * rules refuse, or a malformed body, is 400 [io.ntole.wyr.core.error.ErrorCode.VALIDATION_FAILED].
 */
@Serializable
public data class RenameCategoryRequest(
    public val id: String,
    public val nameSr: String,
    public val nameEn: String,
)
