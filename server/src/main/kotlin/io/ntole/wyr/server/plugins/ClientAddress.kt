package io.ntole.wyr.server.plugins

import io.ktor.server.request.ApplicationRequest

/**
 * The address a request came from, which a per-address rate limit keys by (CLAUDE.md §8b, *Rate
 * limiting*): what [clientIpHeader] says (`ServerConfig.clientIpHeader`), when the server trusts one,
 * and otherwise the socket peer.
 */
internal fun ApplicationRequest.clientAddress(clientIpHeader: String?): String =
    clientAddress(
        peer = local.remoteAddress,
        named = clientIpHeader?.let { name -> headers.getAll(name) }.orEmpty(),
    )

/**
 * The one address [named], the values of the header the proxy in front sets to the address that
 * reached it, or [peer], the socket's other end, when there is not exactly one.
 *
 * The header is trusted only because that proxy overwrites it whatever the client sent, as Cloudflare
 * does `CF-Connecting-IP` in front of Render. Never `X-Forwarded-For`, which every proxy appends to:
 * its leftmost entry is whatever the client wrote, and which entry is the client's depends on how many
 * proxies stand behind the first, which on Render has been reported as both one and two.
 *
 * With no header trusted, [named] is empty and the peer is the client itself when nothing stands in
 * between, as on a laptop. Behind the proxy, a request without the header keys by the proxy, one
 * budget for every such request, and so does one with several values, or a list in one, which the
 * proxy would not have sent.
 */
internal fun clientAddress(
    peer: String,
    named: List<String>,
): String {
    val address = named.singleOrNull()?.trim().orEmpty()
    return if (address.isEmpty() || ',' in address) peer else address
}
