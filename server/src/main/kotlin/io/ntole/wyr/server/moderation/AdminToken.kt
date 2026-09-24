package io.ntole.wyr.server.moderation

import io.ktor.server.application.ApplicationCall
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.server.plugins.ApiFailure
import java.security.MessageDigest

/**
 * The server's admin token (CLAUDE.md §8d, *Moderation*): whoever presents it in
 * [WyrApi.Headers.ADMIN_TOKEN] is the moderator.
 *
 * A presented token is compared in constant time. Both sides are digested with SHA-256 and the
 * digests compared by [sameBytes], [MessageDigest.isEqual], which looks at every byte whatever it
 * finds. `==` on the strings stops at the first character that differs, so how long a wrong guess
 * took to refuse would say how much of it was right, and a guess could be built up a character at a
 * time. Digesting first also puts two arrays of one length in front of the comparison whatever was
 * presented, so its time says nothing about the token's length either. Only the digest is kept.
 *
 * [sameBytes] is a parameter only so a test can see what it is given; nothing else passes one.
 */
class AdminToken(
    token: String,
    internal val sameBytes: (ByteArray, ByteArray) -> Boolean = MessageDigest::isEqual,
) {
    private val expected: ByteArray = digest(token)

    /** Whether [presented] is the token. None never is. */
    fun matches(presented: String?): Boolean = presented != null && sameBytes(digest(presented), expected)

    private fun digest(token: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
}

/**
 * Refuses the call with 403 [io.ntole.wyr.core.error.ErrorCode.FORBIDDEN] unless it carries [token]
 * in [WyrApi.Headers.ADMIN_TOKEN].
 *
 * Every admin route calls this before it reads anything else of the request, so a caller without the
 * token learns nothing about what it asked: not whether a question exists, nor whether its body would
 * have parsed. Never 401, which is the player's session's answer: the bearer token plays no part
 * here, and a client answers a 401 by refreshing the player's session and then replacing it.
 */
fun ApplicationCall.requireAdmin(token: AdminToken) {
    if (!token.admits(this)) throw ApiFailure.forbidden()
}

/**
 * Whether [call] carries the token in [WyrApi.Headers.ADMIN_TOKEN]: exactly the calls [requireAdmin]
 * lets through, which is how the failed-token rate limit tells them apart (`installRateLimits`).
 */
fun AdminToken.admits(call: ApplicationCall): Boolean = matches(call.request.headers[WyrApi.Headers.ADMIN_TOKEN])
