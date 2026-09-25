package io.ntole.wyr.core.domain.language

/**
 * Serbian's two alphabets (CLAUDE.md §8f, *Languages*): the app's Serbian is written in Cyrillic, and
 * its Latin is made from that by [toLatin], never written by hand, so the two cannot say different
 * things. Pure, so question texts can go through it later as they are.
 */
public object SerbianScript {
    /**
     * [text] with every letter of the Serbian Cyrillic alphabet written in the Serbian Latin one, and
     * everything else as it was: Latin letters, digits, punctuation, spacing, and any Cyrillic letter
     * Serbian does not use (Я, Щ, Ы and the rest), which has no Serbian Latin spelling.
     *
     * Three letters become two: Љ, Њ and Џ are Lj, Nj and Dž, and LJ, NJ and DŽ in a word written in
     * capitals, as a heading or a shout is (ЉУБАВ is LJUBAV, where Љубав is Ljubav). A word is a run
     * of letters, so a hyphen or a full stop ends one, and it counts as written in capitals when it
     * has two letters or more and none of them small. A word of one capital letter, as an initial
     * is, gets a capital and a small letter: Љ. is Lj.
     *
     * The rest are one letter for one, Ђ, Ж, Ћ, Ч and Ш as the precomposed Đ, Ž, Ć, Č and Š. Dž is two
     * letters, D and ž, never the one-character digraph Unicode also has, which keyboards do not
     * type and searches do not find.
     */
    public fun toLatin(text: String): String {
        val latin = StringBuilder(text.length)
        var start = 0
        while (start < text.length) {
            if (!text[start].isLetter()) {
                latin.append(text[start])
                start++
                continue
            }
            var end = start
            while (end < text.length && text[end].isLetter()) end++
            appendWord(latin, text.substring(start, end))
            start = end
        }
        return latin.toString()
    }

    private fun appendWord(
        latin: StringBuilder,
        word: String,
    ) {
        val inCapitals = word.length > 1 && word.none(Char::isLowerCase)
        word.forEach { letter ->
            val spelled = LATIN[letter]
            // In capitals only Lj, Nj and Dž change: every other capital is one letter already.
            when {
                spelled == null -> latin.append(letter)
                inCapitals -> latin.append(spelled.uppercase())
                else -> latin.append(spelled)
            }
        }
    }

    /** The Serbian Cyrillic alphabet, capitals, in its own order. */
    private const val CYRILLIC = "АБВГДЂЕЖЗИЈКЛЉМНЊОПРСТЋУФХЦЧЏШ"

    /** Each of [CYRILLIC]'s letters in Latin, in the same order, as a capital begins a word. */
    private val LATIN_CAPITALS = "A B V G D Đ E Ž Z I J K L Lj M N Nj O P R S T Ć U F H C Č Dž Š".split(" ")

    /** Every Serbian Cyrillic letter, capital and small, to its Latin spelling. */
    private val LATIN: Map<Char, String> =
        CYRILLIC
            .toList()
            .zip(LATIN_CAPITALS)
            .flatMap { (capital, spelled) ->
                listOf(capital to spelled, capital.lowercaseChar() to spelled.lowercase())
            }.toMap()
}
