package io.ntole.wyr.language

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import io.ntole.wyr.core.domain.language.SerbianScript

/**
 * Every word of the game's own that has been translated (CLAUDE.md §8f), one value per [Language]:
 * a screen reads them from [LocalStrings] and writes none of its own.
 *
 * Plain Kotlin values rather than compose resources: three languages, one of them made from another
 * by a function, which string resources cannot express, and every text checked by the compiler, a
 * missing one being a constructor that does not compile rather than a key that fails at run time.
 */
data class Strings(
    /** The game's name, on the Home screen. */
    val gameName: String,
    /** The Home screen's one button, into the game. */
    val play: String,
    /** The Play screen's words. */
    val playScreen: PlayStrings,
    /** The home icon's name, for a screen reader: back to the Home screen. */
    val home: String,
    /** The account icon's name, for a screen reader: to the Account screen. */
    val account: String,
    /** The back arrow's name, for a screen reader: to the screen before. */
    val back: String,
    /** The label of the language switch on the Account screen, for a screen reader. */
    val language: String,
    /** The Account screen's own words and those of the pages opened from it. */
    val accountScreens: AccountStrings,
) {
    /**
     * These strings with [transform] applied to every one of them, which is how Serbian Latin is made.
     * A text added to [Strings] goes through here too, or [SerbianLatinStrings] would leave it in
     * Cyrillic, which `StringsTest` catches.
     */
    internal fun map(transform: (String) -> String): Strings =
        Strings(
            gameName = transform(gameName),
            play = transform(play),
            playScreen = playScreen.map(transform),
            home = transform(home),
            account = transform(account),
            back = transform(back),
            language = transform(language),
            accountScreens = accountScreens.map(transform),
        )
}

/** The source text, written by hand. */
val SerbianCyrillicStrings: Strings =
    Strings(
        gameName = "Шта би радије?",
        play = "Играј",
        playScreen = SerbianCyrillicPlayStrings,
        home = "Почетна",
        account = "Налог",
        back = "Назад",
        language = "Језик",
        accountScreens = SerbianCyrillicAccountStrings,
    )

/** Made from [SerbianCyrillicStrings], never written by hand, so the two cannot say different things. */
val SerbianLatinStrings: Strings = SerbianCyrillicStrings.map(SerbianScript::toLatin)

val EnglishStrings: Strings =
    Strings(
        gameName = "Would You Rather?",
        play = "Play",
        playScreen = EnglishPlayStrings,
        home = "Home",
        account = "Account",
        back = "Back",
        language = "Language",
        accountScreens = EnglishAccountStrings,
    )

/** The strings [language] is written in. */
fun stringsOf(language: Language): Strings =
    when (language) {
        Language.SERBIAN_CYRILLIC -> SerbianCyrillicStrings
        Language.SERBIAN_LATIN -> SerbianLatinStrings
        Language.ENGLISH -> EnglishStrings
    }

/** The strings of the language the game is shown in; Serbian Cyrillic, the default, until one is provided. */
val LocalStrings = staticCompositionLocalOf { stringsOf(Language.DEFAULT) }

/** Shows [content] in [language]: everything under it reads [LocalStrings] in that language. */
@Composable
fun WyrStrings(
    language: Language,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalStrings provides stringsOf(language), content = content)
}
