package io.ntole.wyr.submit

import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.submission.OptionProblem
import io.ntole.wyr.language.EnglishStrings
import io.ntole.wyr.language.SerbianCyrillicStrings
import io.ntole.wyr.language.SerbianLatinStrings
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
        assertEquals("Пошаљи · 1\u00A0П", sendText(SerbianCyrillicStrings))
        assertEquals("Pošalji · 1\u00A0P", sendText(SerbianLatinStrings))
        assertEquals("Send · 1\u00A0P", sendText(EnglishStrings))
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
    }
}
