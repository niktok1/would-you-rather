package io.ntole.wyr.core.domain.submission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The server's rules for an option (`checkedSubmission`), as a client's form applies them. */
class SubmissionRulesTest {
    @Test
    fun `an option of one line in range is a question's`() {
        val longest = "x".repeat(SubmissionRules.MAX_OPTION_LENGTH)
        listOf("Fly", "  Fly  ", "Be able to fly", "Eat crème brûlée 🍮", longest).forEach { option ->
            assertNull(SubmissionRules.optionProblem(option), "\"$option\"")
        }
    }

    @Test
    fun `a blank option is refused`() {
        listOf("", " ", "\t\n ").forEach { text ->
            assertEquals(OptionProblem.BLANK, SubmissionRules.optionProblem(text), "\"$text\"")
        }
    }

    @Test
    fun `an option of the longest length fits and one more does not`() {
        val longest = "x".repeat(SubmissionRules.MAX_OPTION_LENGTH)

        assertNull(SubmissionRules.optionProblem(longest))
        assertEquals(OptionProblem.TOO_LONG, SubmissionRules.optionProblem(longest + "x"))
    }

    @Test
    fun `padding does not count towards the length`() {
        val longest = "x".repeat(SubmissionRules.MAX_OPTION_LENGTH)

        assertNull(SubmissionRules.optionProblem("  $longest \n"))
    }

    @Test
    fun `the length is counted in UTF-16 units as the server counts it`() {
        // Each emoji is two units, so half as many fill the limit.
        val longest = "😀".repeat(SubmissionRules.MAX_OPTION_LENGTH / 2)

        assertNull(SubmissionRules.optionProblem(longest))
        assertEquals(OptionProblem.TOO_LONG, SubmissionRules.optionProblem(longest + "x"))
    }

    @Test
    fun `an option of more than one line is refused`() {
        // A whitespace control character goes at either end like a space, but none may be left inside.
        listOf("a\nb", "a\r\nb", "a\tb", "a b", "a b", "a\u0000b", "a\u007Fb").forEach { text ->
            assertEquals(OptionProblem.NOT_ONE_LINE, SubmissionRules.optionProblem(text), "\"$text\"")
        }
    }

    @Test
    fun `two options the same trimmed and ignoring case are the same`() {
        assertTrue(SubmissionRules.sameOptions("Fly", "fly"))
        assertTrue(SubmissionRules.sameOptions("  Fly", "FLY  "))
        assertFalse(SubmissionRules.sameOptions("Fly", "Swim"))
        assertFalse(SubmissionRules.sameOptions("Fly", "Fly high"))
    }
}
