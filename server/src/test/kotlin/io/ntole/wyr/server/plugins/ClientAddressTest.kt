package io.ntole.wyr.server.plugins

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which address a per-address rate limit keys by, from the socket peer and the values of the header
 * the proxy in front sets: on Render, Cloudflare's `CF-Connecting-IP` (`render.yaml`).
 */
class ClientAddressTest {
    @Test
    fun `with no header trusted the socket peer is the address`() {
        assertEquals(PEER, clientAddress(PEER, named = emptyList()))
    }

    @Test
    fun `the header's one address is the client's, trimmed`() {
        assertEquals(CLIENT, clientAddress(PEER, listOf(CLIENT)))
        assertEquals(CLIENT, clientAddress(PEER, listOf("  $CLIENT ")))
        assertEquals("2001:db8::7", clientAddress(PEER, listOf("2001:db8::7")))
    }

    @Test
    fun `a header that is blank, sent twice or holding a list falls back to the peer`() {
        // None of these is what the proxy sends, so none of it is taken.
        listOf(
            listOf(""),
            listOf("   "),
            listOf(CLIENT, "198.51.100.1"),
            listOf("198.51.100.1, $CLIENT"),
        ).forEach { named -> assertEquals(PEER, clientAddress(PEER, named), named.toString()) }
    }

    private companion object {
        const val PEER = "10.226.1.1"
        const val CLIENT = "81.97.145.24"
    }
}
