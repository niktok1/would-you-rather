package io.ntole.wyr.language

/**
 * The words of sharing a question (CLAUDE.md §8d, *Sharing*; §8f), a part of [PlayStrings] of their own,
 * since the Question details screen shares too: the button's name, the dialog's switch and buttons,
 * what the image says, and the message the image goes with.
 */
data class ShareStrings(
    /** The share icon's name for a screen reader, and the dialog's button that shares. */
    val share: String,
    /** The dialog's switch: the crowd's split on the image, and the player's pick. */
    val showResults: String,
    /** On the image, over the card the player picked. */
    val myPick: String,
    /** Under the image's two cards: where to play, a template whose `{0}` is [GOOGLE_PLAY]. */
    val invite: String,
    /** The message the image goes with, a template whose `{0}` is the game's store page. */
    val message: String,
    /** The desktop's share: the image and the message are on the clipboard. */
    val copied: String,
    /** A browser that cannot share a file: the image is downloaded, the message copied. */
    val saved: String,
    /** Nothing could share it. */
    val failed: String,
    /** The dialog's way out once the image was copied or saved. */
    val close: String,
) {
    /** These strings with [transform] applied to every one of them, as [Strings.map] does. */
    internal fun map(transform: (String) -> String): ShareStrings =
        ShareStrings(
            share = transform(share),
            showResults = transform(showResults),
            myPick = transform(myPick),
            invite = transform(invite),
            message = transform(message),
            copied = transform(copied),
            saved = transform(saved),
            failed = transform(failed),
            close = transform(close),
        )
}

/** The source text, written by hand; Serbian Latin is made from it with the rest of [Strings]. */
internal val SerbianCyrillicShareStrings: ShareStrings =
    ShareStrings(
        share = "Подели",
        showResults = "Прикажи резултате",
        myPick = "Мој избор",
        invite = "Играј и ти на {0}",
        message = "Шта би ти радије? Играј и ти: {0}",
        copied = "Слика је копирана.",
        saved = "Слика је сачувана.",
        failed = "Дељење није успело.",
        close = "Затвори",
    )

internal val EnglishShareStrings: ShareStrings =
    ShareStrings(
        share = "Share",
        showResults = "Show results",
        myPick = "My pick",
        invite = "Play it on {0}",
        message = "What would you rather? Play it too: {0}",
        copied = "Image copied.",
        saved = "Image saved.",
        failed = "Couldn't share it.",
        close = "Close",
    )
