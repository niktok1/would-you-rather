package io.ntole.wyr.language

/**
 * The Auth page's one line under Register (CLAUDE.md §8d, *The Account screen*), as
 * [AccountStrings.termsLine]: a template, [line], whose `{0}` is [terms] and `{1}` [privacyPolicy],
 * each a link to its page on the site, so each language puts the two nouns where its grammar wants
 * them, inflected as it needs.
 */
data class TermsLineStrings(
    val line: String,
    val terms: String,
    val privacyPolicy: String,
) {
    /** These strings with [transform] applied to every one of them, as [Strings.map] asks. */
    internal fun map(transform: (String) -> String): TermsLineStrings =
        TermsLineStrings(
            line = transform(line),
            terms = transform(terms),
            privacyPolicy = transform(privacyPolicy),
        )
}

/** The source text, written by hand. */
internal val SerbianCyrillicTermsLineStrings: TermsLineStrings =
    TermsLineStrings(
        line = "Регистрацијом прихваташ {0} и {1}.",
        terms = "услове",
        privacyPolicy = "политику приватности",
    )

internal val EnglishTermsLineStrings: TermsLineStrings =
    TermsLineStrings(
        line = "By registering you accept the {0} and the {1}.",
        terms = "terms",
        privacyPolicy = "privacy policy",
    )
