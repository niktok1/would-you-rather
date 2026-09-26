package io.ntole.wyr.language

/**
 * The words about Google Play Games Services (CLAUDE.md §8a, *Play Games sign-in*; §8f), a part of
 * [Strings] of their own, on the Account screen's card and the Auth page. Each names the service by
 * [GOOGLE_PLAY], the same in every language, a template's `{0}`, which [fill] fills in: a brand's
 * name is not translated, and a Serbian text holds no Latin letter.
 */
data class PlayGamesStrings(
    /**
     * Who is playing, on the Account screen's card, for a player registered by Play Games alone, with no
     * username: *Google Play Игре*, where a guest's card says Гост.
     */
    val name: String,
    /**
     * The quiet link, on the card of a player registered by Play Games alone, to the Auth page's
     * Register form: a username and a password log them in where there is no Play Games, on iOS and
     * the web.
     */
    val addUsername: String,
    /**
     * The Auth page's button that signs in with Play Games, where Play Games is set up and the player is
     * not linked to it yet: *Пријави се преко Google Play Игара*.
     */
    val signIn: String,
) {
    /** These strings with [transform] applied to every one of them, as [Strings.map] does. */
    internal fun map(transform: (String) -> String): PlayGamesStrings =
        PlayGamesStrings(
            name = transform(name),
            addUsername = transform(addUsername),
            signIn = transform(signIn),
        )
}

/**
 * Google Play, as the service is named in every language: a brand, not translated, and so not in
 * [Strings], whose Serbian texts hold no Latin letter, as [USERNAME_CHARACTERS] is not.
 */
const val GOOGLE_PLAY: String = "Google Play"

/** The source text, written by hand; Serbian Latin is made from it with the rest of [Strings]. */
internal val SerbianCyrillicPlayGamesStrings: PlayGamesStrings =
    PlayGamesStrings(
        name = "{0} Игре",
        addUsername = "Додај корисничко име",
        signIn = "Пријави се преко {0} Игара",
    )

internal val EnglishPlayGamesStrings: PlayGamesStrings =
    PlayGamesStrings(
        name = "{0} Games",
        addUsername = "Add a username",
        signIn = "Sign in with {0} Games",
    )
