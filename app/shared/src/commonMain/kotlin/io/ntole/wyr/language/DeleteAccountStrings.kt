package io.ntole.wyr.language

/**
 * The words of deleting an account on the Account screen (CLAUDE.md §8d, *The Account screen*;
 * §8a, *Deleting an account*), as [AccountStrings.deleteAccount]: made in each language as the rest of
 * [Strings] is, and checked by the same tests. What went wrong is said in the Account screen's words.
 */
data class DeleteAccountStrings(
    /** The quiet button at the bottom of the Account screen, and the site's name for it. */
    val button: String,
    /** The confirm dialog's one line: everything goes, for good. */
    val warning: String,
    /** The dialog's button that deletes. */
    val confirm: String,
) {
    /** These strings with [transform] applied to every one of them, as [Strings.map] asks. */
    internal fun map(transform: (String) -> String): DeleteAccountStrings =
        DeleteAccountStrings(
            button = transform(button),
            warning = transform(warning),
            confirm = transform(confirm),
        )
}

/** The source text, written by hand. */
internal val SerbianCyrillicDeleteAccountStrings: DeleteAccountStrings =
    DeleteAccountStrings(
        button = "Обриши налог",
        warning = "Налог и све у њему нестаће заувек.",
        confirm = "Обриши",
    )

internal val EnglishDeleteAccountStrings: DeleteAccountStrings =
    DeleteAccountStrings(
        button = "Delete account",
        warning = "The account and all in it go for good.",
        confirm = "Delete",
    )
