package io.ntole.wyr.submit

import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.submission.OptionProblem
import io.ntole.wyr.language.EnglishStrings
import io.ntole.wyr.language.Language
import io.ntole.wyr.language.SerbianCyrillicStrings
import io.ntole.wyr.language.SerbianLatinStrings
import io.ntole.wyr.language.stringsOf
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the Submit screen's form says, in the player's words. */
class SubmitScreenTest {
    /**
     * The cost is on the button, in every language, from the one constant the client keeps, in the
     * points' one unit, the Play screen's and the Account screen's.
     */
    @Test
    fun `Send names what a question costs in every language`() {
        // A coin and the number on screen, which a screen reader hears as the points are heard everywhere.
        assertEquals("Пошаљи · Поени: 1", sendText(SerbianCyrillicStrings))
        assertEquals("Pošalji · Poeni: 1", sendText(SerbianLatinStrings))
        assertEquals("Send · Points: 1", sendText(EnglishStrings))
    }

    @Test
    fun `each option problem reads as what to put right`() {
        assertEquals("One line, up to 200 characters.", optionHint(null, same = false, ENGLISH))
        assertEquals("Write something.", optionHint(OptionProblem.BLANK, same = false, ENGLISH))
        assertEquals("At most 200 characters.", optionHint(OptionProblem.TOO_LONG, same = false, ENGLISH))
        assertEquals("One line, no line breaks.", optionHint(OptionProblem.NOT_ONE_LINE, same = false, ENGLISH))
        assertEquals("The two options must differ.", optionHint(null, same = true, ENGLISH))
        assertEquals(
            "Један ред, до 200 знакова.",
            optionHint(null, same = false, SerbianCyrillicStrings.accountScreens),
        )
    }

    /**
     * The server's refusal for points is the one short line the form shows while the points are too
     * few, in every language: never the catch-all, which would ask the player to try the same again.
     */
    @Test
    fun `too few points reads as one short line in every language`() {
        val tooFew = SubmitFailure(DomainError.NOT_ENOUGH_POINTS)
        assertEquals("Немаш довољно поена.", failureMessage(tooFew, CYRILLIC))
        Language.entries.map(::stringsOf).forEach { strings ->
            assertEquals(strings.accountScreens.notEnoughPoints, failureMessage(tooFew, strings.accountScreens))
        }
    }

    @Test
    fun `categories that cannot be read say so in one line in every language`() {
        val server = SubmitFailure(DomainError.SERVER)
        assertEquals("Couldn't load the categories.", categoriesFailureText(server, EnglishStrings))
        assertEquals("Kategorije nisu učitane.", categoriesFailureText(server, SerbianLatinStrings))
        Language.entries.map(::stringsOf).forEach { strings ->
            // Offline in the words of the form's other failures.
            val offline = SubmitFailure(DomainError.NETWORK)
            assertEquals(strings.accountScreens.offline, categoriesFailureText(offline, strings))
            val limited = SubmitFailure(DomainError.RATE_LIMITED)
            assertEquals(strings.categoriesUnread, categoriesFailureText(limited, strings))
        }
    }

    @Test
    fun `a failure nobody can act on asks to try again`() {
        assertEquals("Something went wrong. Try again.", failureMessage(SubmitFailure(DomainError.SERVER), ENGLISH))
        assertEquals(
            "Too many tries. Wait a moment.",
            failureMessage(SubmitFailure(DomainError.RATE_LIMITED), ENGLISH),
        )
    }

    private companion object {
        val ENGLISH = EnglishStrings.accountScreens
        val CYRILLIC = SerbianCyrillicStrings.accountScreens
    }
}
