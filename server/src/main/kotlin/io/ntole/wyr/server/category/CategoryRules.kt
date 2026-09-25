package io.ntole.wyr.server.category

import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.category.CategoryDto
import io.ntole.wyr.core.category.CreateCategoryRequest
import io.ntole.wyr.core.category.RenameCategoryRequest
import io.ntole.wyr.server.plugins.ApiFailure
import io.ntole.wyr.server.question.LINE_SEPARATORS
import java.text.Normalizer

/**
 * [request] as the category is stored: both names trimmed, and its id as given or derived from its
 * English name ([categoryIdFor]), or an [ApiFailure.validation] for what the rules of
 * [CreateCategoryRequest] refuse. All of them are 400, as a rejection's reason is: the moderator's
 * client checks them before it lets them send.
 */
internal fun checkedCreation(request: CreateCategoryRequest): CategoryDto {
    val nameSr = checkedName("nameSr", request.nameSr)
    val nameEn = checkedName("nameEn", request.nameEn)
    val id =
        request.id?.let(::checkedId)
            ?: categoryIdFor(nameEn).ifEmpty {
                throw ApiFailure.validation("nameEn gives no id: give one")
            }
    return CategoryDto(id = id, nameSr = nameSr, nameEn = nameEn)
}

/** [request] as the category is renamed, both names trimmed, or refused as [checkedCreation] refuses. */
internal fun checkedRenaming(request: RenameCategoryRequest): CategoryDto =
    CategoryDto(
        id = checkedId(request.id),
        nameSr = checkedName("nameSr", request.nameSr),
        nameEn = checkedName("nameEn", request.nameEn),
    )

/**
 * The id a category named [nameEn] in English gets when none is given: accents taken off its letters,
 * the letters upper-cased, every run of anything but `A`-`Z` and `0`-`9` one `_`, none at either end,
 * and cut to [WyrApi.Limits.MAX_CATEGORY_ID_LENGTH]. Empty for a name of no Latin letter or digit.
 */
internal fun categoryIdFor(nameEn: String): String =
    Normalizer
        .normalize(nameEn, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .uppercase()
        .replace(NOT_ID_CHARACTERS, "_")
        .trim('_')
        .take(WyrApi.Limits.MAX_CATEGORY_ID_LENGTH)
        .trimEnd('_')

private fun checkedId(raw: String): String {
    if (!ID.matches(raw)) {
        throw ApiFailure.validation(
            "a category id is 1 to ${WyrApi.Limits.MAX_CATEGORY_ID_LENGTH} of A-Z, 0-9 and _",
        )
    }
    return raw
}

/** [raw] trimmed, and measured only then, one line as an option is (`checkedSubmission`). */
private fun checkedName(
    field: String,
    raw: String,
): String {
    val name = raw.trim()
    if (name.isEmpty()) throw ApiFailure.validation("$field is blank")
    if (name.length > WyrApi.Limits.MAX_CATEGORY_NAME_LENGTH) {
        throw ApiFailure.validation("$field is over ${WyrApi.Limits.MAX_CATEGORY_NAME_LENGTH} characters")
    }
    if (name.any(Char::isISOControl)) throw ApiFailure.validation("$field has a control character")
    if (name.any { it.category in LINE_SEPARATORS }) throw ApiFailure.validation("$field has a line separator")
    return name
}

private val ID = Regex("[A-Z0-9_]{1,${WyrApi.Limits.MAX_CATEGORY_ID_LENGTH}}")
private val COMBINING_MARKS = Regex("\\p{M}+")
private val NOT_ID_CHARACTERS = Regex("[^A-Z0-9]+")
