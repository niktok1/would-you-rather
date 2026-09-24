package io.ntole.wyr.server.plugins

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which address a per-address rate limit keys by, from the socket peer and `X-Forwarded-For`. Render's
 * chain is three proxies that each append (`render.yaml`): Cloudflare's edge records the client, the
 * first of Render's proxies Cloudflare, and the second the first.
 */
class ClientAddressTest {
    @Test
    fun `with no proxy trusted the socket peer is the address whatever the header says`() {
        assertEquals(PEER, clientAddress(PEER, listOf(RENDER_CHAIN), trustedProxyHops = 0))
        assertEquals(PEER, clientAddress(PEER, emptyList(), trustedProxyHops = 0))
    }

    @Test
    fun `behind Render's three proxies the client is the third entry from the right`() {
        assertEquals(CLIENT, clientAddress(PEER, listOf(RENDER_CHAIN), trustedProxyHops = 3))
    }

    @Test
    fun `entries the client sent itself are left of the proxies' and never read`() {
        listOf(
            "1.2.3.4, $RENDER_CHAIN",
            "1.2.3.4, 5.6.7.8, $RENDER_CHAIN",
            "$CLIENT, $RENDER_CHAIN",
            "not an address at all, $RENDER_CHAIN",
        ).forEach { forwarded ->
            assertEquals(CLIENT, clientAddress(PEER, listOf(forwarded), trustedProxyHops = 3), forwarded)
        }
    }

    @Test
    fun `entries spread over several header lines are read in order`() {
        val lines = listOf("1.2.3.4", "$CLIENT, 172.71.195.123", "10.226.90.65")

        assertEquals(CLIENT, clientAddress(PEER, lines, trustedProxyHops = 3))
    }

    @Test
    fun `entries are trimmed`() {
        assertEquals(CLIENT, clientAddress(PEER, listOf("  $CLIENT ,172.71.195.123,  10.226.90.65 "), 3))
    }

    @Test
    fun `a chain shorter than the proxies trusted, or an empty entry in its place, falls back to the peer`() {
        // Too short to have come through every proxy, so every entry could be the client's own.
        assertEquals(PEER, clientAddress(PEER, listOf("172.71.195.123, 10.226.90.65"), trustedProxyHops = 3))
        assertEquals(PEER, clientAddress(PEER, emptyList(), trustedProxyHops = 3))
        assertEquals(PEER, clientAddress(PEER, listOf(", 172.71.195.123, 10.226.90.65"), trustedProxyHops = 3))
    }

    private companion object {
        const val PEER = "10.226.1.1"
        const val CLIENT = "81.97.145.24"

        /** As a request that sent no X-Forwarded-For of its own reaches a service on Render. */
        const val RENDER_CHAIN = "$CLIENT, 172.71.195.123, 10.226.90.65"
    }
}
