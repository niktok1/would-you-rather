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

/**
 * This template cut at its placeholders: the text between them, and for each placeholder its index,
 * in order, so a screen can put something other than a string where one is (a link, a coin). A
 * `{n}` with no value is text as it stands.
 */
internal fun String.parts(): List<TemplatePart> {
    val parts = mutableListOf<TemplatePart>()
    var at = 0
    PLACEHOLDER.findAll(this).forEach { match ->
        if (match.range.first > at) parts += TemplatePart.Text(substring(at, match.range.first))
        parts += TemplatePart.Value(match.groupValues[1].toInt())
        at = match.range.last + 1
    }
    if (at < length) parts += TemplatePart.Text(substring(at))
    return parts
}

/** A piece of a template ([parts]): text as it stands, or the place of the value at [Value.index]. */
internal sealed interface TemplatePart {
    data class Text(
        val text: String,
    ) : TemplatePart

    data class Value(
        val index: Int,
    ) : TemplatePart
}

/** A template's placeholder, `{0}` to `{9}`. */
internal val PLACEHOLDER = Regex("""\{(\d)\}""")
