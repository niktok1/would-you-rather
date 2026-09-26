package io.ntole.wyr.language

import io.ntole.wyr.core.domain.language.SerbianScript

/**
 * A question's [option] as the game shows it in [language] (CLAUDE.md §8f): in Serbian Latin made
 * Latin by [SerbianScript.toLatin], as every Serbian Latin text is, and in Serbian Cyrillic and in
 * English as its author wrote it, since questions are not translated. [SerbianScript.toLatin] leaves a
 * Latin letter as it was, so an option written in Latin, or in English, reads the same in each. The
 * one place the choice is made, for the Play screen's cards and My questions alike.
 */
fun optionText(
    option: String,
    language: Language,
): String = if (language == Language.SERBIAN_LATIN) SerbianScript.toLatin(option) else option
