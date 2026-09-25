package io.ntole.wyr.core.domain.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The server's rules exactly (CLAUDE.md §8a, *Accounts*), as `AccountFlowTest` holds the server to them. */
class AccountRulesTest {
    @Test
    fun `a name of letters digits and underscores in either case is an account's`() {
        listOf("bob", "Bob_1", "BOB_1", "a_b", "123", "abcdefghijklmnopqrst").forEach { name ->
            assertNull(AccountRules.usernameProblem(name), name)
        }
    }

    @Test
    fun `a name is measured as the server measures it`() {
        assertEquals(UsernameProblem.TOO_SHORT, AccountRules.usernameProblem(""))
        assertEquals(UsernameProblem.TOO_SHORT, AccountRules.usernameProblem("ab"))
        assertEquals(UsernameProblem.TOO_LONG, AccountRules.usernameProblem("abcdefghijklmnopqrstu"))
    }

    @Test
    fun `a name holding anything else is refused whatever its length`() {
        // Nothing is trimmed, so a space at either end is refused as one in the middle is.
        listOf("a b", " bob", "bob ", "bob!", "bøb", "bob.1", "b-o-b", "a b c d e f g h i j k l").forEach { name ->
            assertEquals(UsernameProblem.INVALID_CHARACTER, AccountRules.usernameProblem(name), "\"$name\"")
        }
    }

    @Test
    fun `a password of any characters in range is an account's`() {
        listOf("secret", "correct horse battery staple", "p@ss wörd", "      ", "x".repeat(128)).forEach { password ->
            assertNull(AccountRules.passwordProblem(password), password)
        }
    }

    @Test
    fun `a password is measured in UTF-16 code units`() {
        assertEquals(PasswordProblem.TOO_SHORT, AccountRules.passwordProblem("12345"))
        assertEquals(PasswordProblem.TOO_LONG, AccountRules.passwordProblem("x".repeat(129)))
        // Three emoji are six units, as String.length counts on the server.
        assertNull(AccountRules.passwordProblem("😀😀😀"))
    }
}
