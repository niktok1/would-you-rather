package io.ntole.wyr.core.domain.language

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Serbian Cyrillic to Latin, letter by letter and word by word (CLAUDE.md §8f). Every expected text is
 * written out here, not worked out from the function's own table.
 */
class SerbianScriptTest {
    @Test
    fun `every small letter has its Latin spelling`() {
        assertEquals(
            "a b v g d đ e ž z i j k l lj m n nj o p r s t ć u f h c č dž š",
            SerbianScript.toLatin("а б в г д ђ е ж з и ј к л љ м н њ о п р с т ћ у ф х ц ч џ ш"),
        )
    }

    /** A capital on its own is a word of one letter, so Љ, Њ and Џ are a capital and a small letter. */
    @Test
    fun `every capital on its own has its Latin spelling`() {
        assertEquals(
            "A B V G D Đ E Ž Z I J K L Lj M N Nj O P R S T Ć U F H C Č Dž Š",
            SerbianScript.toLatin("А Б В Г Д Ђ Е Ж З И Ј К Л Љ М Н Њ О П Р С Т Ћ У Ф Х Ц Ч Џ Ш"),
        )
    }

    @Test
    fun `the whole alphabet as one word in capitals has its digraphs in capitals`() {
        assertEquals(
            "ABVGDĐEŽZIJKLLJMNNJOPRSTĆUFHCČDŽŠ",
            SerbianScript.toLatin("АБВГДЂЕЖЗИЈКЛЉМНЊОПРСТЋУФХЦЧЏШ"),
        )
    }

    @Test
    fun `the whole alphabet as one small word keeps every letter small`() {
        assertEquals(
            "abvgdđežzijklljmnnjoprstćufhcčdžš",
            SerbianScript.toLatin("абвгдђежзијклљмнњопрстћуфхцчџш"),
        )
    }

    @Test
    fun `a digraph that begins a capitalised word is a capital and a small letter`() {
        assertEquals("Ljubav Njegoš Džep", SerbianScript.toLatin("Љубав Његош Џеп"))
    }

    @Test
    fun `a digraph inside or at the end of a small word stays small`() {
        assertEquals("Biljana konj hodža", SerbianScript.toLatin("Биљана коњ хоџа"))
    }

    @Test
    fun `a word in capitals has its digraphs in capitals wherever they fall`() {
        assertEquals("LJUBAV NJEGOŠ DŽEP BILJANA KONJ HODŽA", SerbianScript.toLatin("ЉУБАВ ЊЕГОШ ЏЕП БИЉАНА КОЊ ХОЏА"))
    }

    @Test
    fun `each word is spelled by its own case whatever its neighbours are`() {
        assertEquals("LJUBAV je Ljubav a ljubav", SerbianScript.toLatin("ЉУБАВ је Љубав а љубав"))
    }

    @Test
    fun `punctuation ends a word`() {
        assertEquals("LJUBAV-Njiva Lj. DŽEP.", SerbianScript.toLatin("ЉУБАВ-Њива Љ. ЏЕП."))
        assertEquals("Lj.Nj.", SerbianScript.toLatin("Љ.Њ."))
    }

    @Test
    fun `Latin text comes back as it was`() {
        val latin = "Would you rather? Šta bi radije, Đorđe? LJUBAV Ljubav džep NJIVA Ćao Čačak Žarko"
        assertEquals(latin, SerbianScript.toLatin(latin))
    }

    @Test
    fun `Latin and Cyrillic in one text change only the Cyrillic`() {
        assertEquals("Play Igraj OK Džep", SerbianScript.toLatin("Play Играј OK Џеп"))
    }

    @Test
    fun `punctuation digits and spacing come back as they were`() {
        assertEquals(
            "1, 2. (3)! – „Da“ … ? ; : \"ne\" 'x' 42%\n\tkraj",
            SerbianScript.toLatin("1, 2. (3)! – „Да“ … ? ; : \"не\" 'x' 42%\n\tкрај"),
        )
    }

    /** Ѐ, ѐ, Ѝ and ѝ each written as one character, not a letter and an accent; È, è, Ì and ì too. */
    @Test
    fun `the accented letters keep their accent in Latin`() {
        assertEquals("\u00C8 \u00E8 \u00CC \u00EC", SerbianScript.toLatin("\u0400 \u0450 \u040D \u045D"))
        assertEquals("Reci ì. RECI Ì!", SerbianScript.toLatin("Реци \u045D. РЕЦИ \u040D!"))
    }

    @Test
    fun `Cyrillic letters Serbian does not use come back as they were`() {
        assertEquals("Я Щ Ы Э Ё й ъ ь ю ї є і ґ ѓ ќ ѕ", SerbianScript.toLatin("Я Щ Ы Э Ё й ъ ь ю ї є і ґ ѓ ќ ѕ"))
    }

    @Test
    fun `an empty text stays empty`() {
        assertEquals("", SerbianScript.toLatin(""))
    }

    @Test
    fun `a sentence reads as Serbian Latin`() {
        assertEquals(
            "Šta bi radije: đak ili učitelj? Džaba se čačkaš, Njegoše!",
            SerbianScript.toLatin("Шта би радије: ђак или учитељ? Џаба се чачкаш, Његоше!"),
        )
    }
}
