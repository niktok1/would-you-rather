package io.ntole.wyr.core.category

import kotlinx.serialization.Serializable

/**
 * Add a category (CLAUDE.md §8d, *Categories*), with a POST to
 * [io.ntole.wyr.core.api.WyrApi.Paths.ADMIN_CATEGORIES], answered 201 with its [CategoryDto].
 *
 * [nameSr] is its name in Serbian, in Cyrillic, and [nameEn] in English. The server trims each, as
 * Kotlin's `trim()` does, and stores it trimmed; trimmed, each must be 1 to
 * [io.ntole.wyr.core.api.WyrApi.Limits.MAX_CATEGORY_NAME_LENGTH] long and one line, free of control
 * characters and of U+2028 and U+2029, as a submitted option must be.
 *
 * [id] is what the wire names the category by, forever: 1 to
 * [io.ntole.wyr.core.api.WyrApi.Limits.MAX_CATEGORY_ID_LENGTH] of `A`-`Z`, `0`-`9` and `_`, exactly as
 * sent. Null, the default, derives it from [nameEn] once trimmed: accents taken off its letters, the
 * letters upper-cased, every run of anything but `A`-`Z` and `0`-`9` one `_`, none at either end, and
 * cut to the longest id, so "Fast food" is `FAST_FOOD` and "Café" `CAFE`. A name that derives nothing,
 * one of no Latin letter or digit, needs an id given.
 *
 * Anything the rules refuse is 400 [io.ntole.wyr.core.error.ErrorCode.VALIDATION_FAILED], as a
 * rejection's reason is: the moderator's client checks it before it lets them send. An id a category
 * has already, given or derived, is 409 [io.ntole.wyr.core.error.ErrorCode.CATEGORY_EXISTS], and of
 * two creations racing for one id exactly one is made.
 */
@Serializable
public data class CreateCategoryRequest(
    public val id: String? = null,
    public val nameSr: String,
    public val nameEn: String,
)
