package io.ntole.wyr.language

/**
 * The Submit form's one line under Send (CLAUDE.md §8d, *Submitting*), as [AccountStrings.rulesLine]:
 * a template, [line], whose `{0}` is [rules], a link to the site's terms and question rules, so each
 * language puts the noun where its grammar wants it, inflected as it needs.
 */
data class RulesLineStrings(
    val line: String,
    val rules: String,
) {
    /** These strings with [transform] applied to every one of them, as [Strings.map] asks. */
    internal fun map(transform: (String) -> String): RulesLineStrings =
        RulesLineStrings(
            line = transform(line),
            rules = transform(rules),
        )
}

/** The source text, written by hand. */
internal val SerbianCyrillicRulesLineStrings: RulesLineStrings =
    RulesLineStrings(
        line = "Слањем прихваташ {0}.",
        rules = "правила питања",
    )

internal val EnglishRulesLineStrings: RulesLineStrings =
    RulesLineStrings(
        line = "By sending you accept the {0}.",
        rules = "question rules",
    )
