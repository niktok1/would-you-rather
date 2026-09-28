package io.ntole.wyr.about

import io.ntole.wyr.language.Language

/**
 * The game's website, where its legal pages are (CLAUDE.md §8d, *About*): the privacy policy, the terms
 * and question rules, deleting an account, and contact, in Serbian at the game's root and in English
 * under `/en/`. The game's pages are under `/wyr` of the developer's own domain, `ntole.com`, which
 * later apps share (§8b, *The launch*). Not live yet: no page is written.
 */
object Site {
    /** The one base the game's every link to the site is made from. */
    const val BASE_URL: String = "https://ntole.com/wyr"

    /** Where [page] is, in the language the game is shown in: Serbian in either script is the Serbian page. */
    fun url(
        page: SitePage,
        language: Language,
    ): String =
        when (language) {
            Language.ENGLISH -> "$BASE_URL/en/${page.path}"
            Language.SERBIAN_CYRILLIC, Language.SERBIAN_LATIN -> "$BASE_URL/${page.path}"
        }
}

/** The site's pages the game links to, each its file under the site's root and under `/en/`. */
enum class SitePage(
    val path: String,
) {
    PRIVACY("privacy.html"),
    TERMS("terms.html"),
    DELETE_ACCOUNT("delete.html"),
    CONTACT("contact.html"),
}
