package io.ntole.wyr.core.category

import kotlinx.serialization.Serializable

/**
 * Every category the server has ([io.ntole.wyr.core.api.WyrApi.Paths.CATEGORIES]), in the order of
 * categories: when each was added, oldest first, then by id. That is the order every list of a
 * question's categories comes in too. It is no order of names, which would differ by language and by
 * the database's collation: a client sorts by the name it shows, if it sorts at all.
 *
 * A list inside an object rather than a bare array, as `SubmissionListDto` is, so a field can be
 * added later without breaking a client that does not know it.
 */
@Serializable
public data class CategoryListDto(
    public val categories: List<CategoryDto>,
)
