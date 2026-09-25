package io.ntole.wyr.language

/**
 * The languages the game's words come in (CLAUDE.md §8f, *Languages*), in the order the switch on the
 * Account screen lists them. Serbian Cyrillic is the source; Serbian Latin is made from it.
 *
 * [ownName] is the language as the switch names it, in itself, whatever language the screen is in:
 * a player who picked one they cannot read still finds their own. So it is not one of [Strings].
 * [tag] is what the device keeps for it, a BCP 47 tag, which never changes with the enum's names.
 */
enum class Language(
    val ownName: String,
    val tag: String,
) {
    SERBIAN_CYRILLIC(ownName = "Ћирилица", tag = "sr-Cyrl"),
    SERBIAN_LATIN(ownName = "Latinica", tag = "sr-Latn"),
    ENGLISH(ownName = "English", tag = "en"),
    ;

    companion object {
        /**
         * The language of a first launch, whatever the device's own: the game is Serbian first (the
         * user: "main language should be serbian"), so nothing reads the device's locale.
         */
        val DEFAULT: Language = SERBIAN_CYRILLIC

        /** The language [tag] names, or [DEFAULT] for none or one this build does not know. */
        fun ofTag(tag: String?): Language = entries.firstOrNull { it.tag == tag } ?: DEFAULT
    }
}
