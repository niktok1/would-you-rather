package io.ntole.wyr.server.plugins

import io.ktor.http.HttpHeaders
import io.ktor.server.request.ApplicationRequest

/**
 * The address a request came from, which a per-address rate limit keys by (CLAUDE.md §8b, *Rate
 * limiting*), behind [trustedProxyHops] proxies that each append to `X-Forwarded-For`
 * (`ServerConfig.trustedProxyHops`).
 */
internal fun ApplicationRequest.clientAddress(trustedProxyHops: Int): String =
    clientAddress(
        peer = local.remoteAddress,
        forwardedFor = headers.getAll(HttpHeaders.XForwardedFor).orEmpty(),
        trustedProxyHops = trustedProxyHops,
    )

/**
 * [peer], the socket's other end, when no proxy is trusted: `X-Forwarded-For` is then ignored, since
 * any client can send one.
 *
 * Otherwise every proxy appends the address it was reached from, so of [forwardedFor]'s entries, in
 * order across however many header lines, the last [trustedProxyHops] are theirs, and the first of
 * those is what the outermost saw: the client. Whatever is left of it is the client's own to write,
 * so a client sending its own `X-Forwarded-For` only adds entries that are never read. Counted from
 * the right for that reason: the leftmost entry is whatever the client said.
 *
 * A chain shorter than the proxies trusted did not come through them all, so none of it is taken,
 * and [peer] stands in, as it does for an empty entry.
 */
internal fun clientAddress(
    peer: String,
    forwardedFor: List<String>,
    trustedProxyHops: Int,
): String {
    if (trustedProxyHops == 0) return peer
    val entries = forwardedFor.flatMap { line -> line.split(',') }.map(String::trim)
    if (entries.size < trustedProxyHops) return peer
    return entries[entries.size - trustedProxyHops].ifEmpty { peer }
}
