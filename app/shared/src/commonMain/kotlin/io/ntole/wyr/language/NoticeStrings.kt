package io.ntole.wyr.language

/**
 * The words of the notice that a moderator decided one of the player's questions (CLAUDE.md §8d,
 * *Submitting*; §8f), a part of [Strings] of their own. A screen reader's, but for the Android
 * notifications' channel: on screen the notice is a dot, on the account icon and on each new row of My
 * questions, the user asking for less text.
 */
data class NoticeStrings(
    /** A row of My questions whose decision the player has not seen before, as a screen reader hears it. */
    val newMark: String,
    /**
     * The account icon's name while a decision waits to be seen, `{0}` its name, [Strings.account]:
     * *Налог: ново*.
     */
    val accountWithNews: String,
    /**
     * The name of the notifications' channel in the phone's settings (Android), where the player turns
     * them on or off: a decision on one of their questions is all it carries.
     */
    val channel: String,
) {
    /** These strings with [transform] applied to every one of them, as [Strings.map] does. */
    internal fun map(transform: (String) -> String): NoticeStrings =
        NoticeStrings(
            newMark = transform(newMark),
            accountWithNews = transform(accountWithNews),
            channel = transform(channel),
        )
}

/** The source text, written by hand; Serbian Latin is made from it with the rest of [Strings]. */
internal val SerbianCyrillicNoticeStrings: NoticeStrings =
    NoticeStrings(
        newMark = "Ново",
        accountWithNews = "{0}: ново",
        channel = "Твоја питања",
    )

internal val EnglishNoticeStrings: NoticeStrings =
    NoticeStrings(
        newMark = "New",
        accountWithNews = "{0}: new",
        channel = "Your questions",
    )
