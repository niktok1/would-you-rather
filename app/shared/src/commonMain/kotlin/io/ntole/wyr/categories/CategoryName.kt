package io.ntole.wyr.categories

import io.ntole.wyr.core.domain.category.Category
import io.ntole.wyr.core.domain.language.SerbianScript
import io.ntole.wyr.language.Language

/**
 * This category's name in [language] (CLAUDE.md §8f): the Serbian name as the server sends it, in
 * Cyrillic; that name in Latin, made from it by [SerbianScript.toLatin] as every Serbian Latin text of
 * the game is; or the English name. For every screen that names a category, so the choice of name is
 * made here and nowhere else.
 */
fun Category.nameIn(language: Language): String =
    when (language) {
        Language.SERBIAN_CYRILLIC -> nameSr
        Language.SERBIAN_LATIN -> SerbianScript.toLatin(nameSr)
        Language.ENGLISH -> nameEn
    }
