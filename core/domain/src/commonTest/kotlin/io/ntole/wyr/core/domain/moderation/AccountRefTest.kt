package io.ntole.wyr.core.domain.moderation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountRefTest {
    @Test
    fun `a username is lower-cased and trimmed`() {
        assertEquals(AccountRef.Username("leaving_1"), AccountRef.of("  Leaving_1\n"))
    }

    /** A player id is a UUID, which no username can be: 36 characters, with hyphens. */
    @Test
    fun `a player id is an account id`() {
        val id = "0f8fad5b-d9cb-469f-a165-70867728950e"

        assertEquals(AccountRef.Id(id), AccountRef.of(" $id "))
    }

    /** What is no username is sent as an id, for the server to find nobody by, as it would by the name. */
    @Test
    fun `anything that is no username is an account id`() {
        assertEquals(AccountRef.Id("two words"), AccountRef.of("two words"))
        assertEquals(AccountRef.Id("ab"), AccountRef.of("ab"))
    }

    @Test
    fun `nothing typed names nothing`() {
        assertNull(AccountRef.of(""))
        assertNull(AccountRef.of("   "))
    }
}
