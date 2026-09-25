package io.ntole.wyr.core.category

import kotlinx.serialization.Serializable

/**
 * A category questions are filed under (CLAUDE.md §8d, *Categories*): server data, which a moderator
 * adds to without a build, so a client lists them from the server
 * ([io.ntole.wyr.core.api.WyrApi.Paths.CATEGORIES]) rather than naming them itself.
 *
 * [id] is what every other part of the wire names the category by, a question's categories and a
 * `?category=` filter included, and it never changes: 1 to
 * [io.ntole.wyr.core.api.WyrApi.Limits.MAX_CATEGORY_ID_LENGTH] of `A`-`Z`, `0`-`9` and `_`. [nameSr]
 * is its name in Serbian, in Cyrillic, and [nameEn] in English, each one line of at most
 * [io.ntole.wyr.core.api.WyrApi.Limits.MAX_CATEGORY_NAME_LENGTH]. A moderator may rename a category,
 * so a client keeps no name longer than it keeps the list.
 */
@Serializable
public data class CategoryDto(
    public val id: String,
    public val nameSr: String,
    public val nameEn: String,
)
