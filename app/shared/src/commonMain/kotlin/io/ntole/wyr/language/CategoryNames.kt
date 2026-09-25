package io.ntole.wyr.language

import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.language.SerbianScript

/**
 * [category] in the player's words, in [language] (CLAUDE.md §8f): its Serbian name as the server
 * keeps it, in Cyrillic; that name made Latin by [SerbianScript.toLatin], as every Serbian Latin text
 * is; or its English name. The one place the game names a category, the Play screen, the
 * Categories screen and the Submit form alike, so the language is chosen here alone. The server's names, not [Strings]: a moderator
 * adds and renames categories without a build (CLAUDE.md §8d, *Categories*).
 */
fun categoryName(
    category: Category,
    language: Language,
): String =
    when (language) {
        Language.SERBIAN_CYRILLIC -> category.nameSr
        Language.SERBIAN_LATIN -> SerbianScript.toLatin(category.nameSr)
        Language.ENGLISH -> category.nameEn
    }

/** The category [id] in [language], as [known] names it, or the id itself for one not read yet. */
fun categoryName(
    id: String,
    known: List<Category>,
    language: Language,
): String = known.firstOrNull { it.id == id }?.let { categoryName(it, language) } ?: id
