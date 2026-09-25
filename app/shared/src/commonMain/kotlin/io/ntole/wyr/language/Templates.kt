package io.ntole.wyr.language

/**
 * The characters a username may hold (`AccountRules`), written as themselves: the same in every
 * language, so not one of [Strings].
 */
const val USERNAME_CHARACTERS: String = "a–z, 0–9, _"

/**
 * This template with `{0}`, `{1}`... each replaced by [values] in that order, in one pass, so a value
 * holding a `{1}` of its own, a moderator's reason say, is left as it is. A text of [Strings] that
 * holds a number or a name is such a template, so each language puts it where its grammar wants it;
 * `StringsTest` holds every language's copy to the same placeholders.
 */
fun String.fill(vararg values: Any): String =
    PLACEHOLDER.replace(this) { match ->
        values.getOrNull(match.groupValues[1].toInt())?.toString() ?: match.value
    }

/** A template's placeholder, `{0}` to `{9}`. */
internal val PLACEHOLDER = Regex("""\{(\d)\}""")
