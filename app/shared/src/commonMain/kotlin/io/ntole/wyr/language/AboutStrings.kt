package io.ntole.wyr.language

/**
 * The words of the About screen (CLAUDE.md §8d, *About*), as [Strings.aboutScreen]: made in each
 * language as the rest of [Strings] is, and checked by the same tests. The libraries' and licences'
 * names are their own, in every language, so they are not here.
 */
data class AboutStrings(
    /** The About screen, as the Account screen's info icon is named for a screen reader. */
    val title: String,
    /** The app's version, `{0}`, and its build number beside it. */
    val version: String,
    val privacy: String,
    /** The terms, whose rules for questions are one of their sections. */
    val terms: String,
    /** The site's page on deleting an account, in the app and without it. */
    val deleteAccount: String,
    val contact: String,
    /** The heading of the libraries the game ships with, each with its licence. */
    val licences: String,
    /**
     * The label over the player's account id, which they send to have their account deleted by email
     * (CLAUDE.md §8a, *Deleting an account*, *By a moderator*).
     */
    val accountId: String,
    /** The copy button beside the account id, as a screen reader names it. */
    val copyAccountId: String,
    /** Said beside the label once the account id is copied. */
    val copied: String,
) {
    /** These strings with [transform] applied to every one of them, as [Strings.map] asks. */
    internal fun map(transform: (String) -> String): AboutStrings =
        AboutStrings(
            title = transform(title),
            version = transform(version),
            privacy = transform(privacy),
            terms = transform(terms),
            deleteAccount = transform(deleteAccount),
            contact = transform(contact),
            licences = transform(licences),
            accountId = transform(accountId),
            copyAccountId = transform(copyAccountId),
            copied = transform(copied),
        )
}

/** The source text, written by hand. */
internal val SerbianCyrillicAboutStrings: AboutStrings =
    AboutStrings(
        title = "О игри",
        version = "Верзија {0}",
        privacy = "Политика приватности",
        terms = "Услови и правила питања",
        deleteAccount = "Брисање налога",
        contact = "Контакт",
        licences = "Лиценце отвореног кода",
        accountId = "ИД налога",
        copyAccountId = "Копирај ИД налога",
        copied = "Копирано",
    )

internal val EnglishAboutStrings: AboutStrings =
    AboutStrings(
        title = "About",
        version = "Version {0}",
        privacy = "Privacy policy",
        terms = "Terms and question rules",
        deleteAccount = "Deleting an account",
        contact = "Contact",
        licences = "Open-source licences",
        accountId = "Account ID",
        copyAccountId = "Copy account ID",
        copied = "Copied",
    )
