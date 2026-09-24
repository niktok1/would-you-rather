package io.ntole.wyr.core.auth

import kotlinx.serialization.Serializable

/**
 * A newly minted guest's session, and the secret that recovers the guest (CLAUDE.md §8a, *Recovery*).
 *
 * Returned by the guest-session endpoint alone. Its fields are a [SessionDto]'s, every one under the
 * same name, and [recoverySecret] beside them, so a client that knows no recovery reads it as the
 * [SessionDto] it always did and ignores the secret. A refresh and a recovery answer a plain
 * [SessionDto], so the secret never travels again.
 *
 * [recoverySecret] is an opaque long-lived credential of which the server stores only a hash, as it
 * does of [refreshToken], but it never rotates: whoever presents it opens a session of their own for
 * this player, as often as they like, until the player replaces it. So it is kept apart from the
 * session, in whatever store the platform hands on to the device that replaces this one, is never
 * logged, and is sent nowhere but the recovery endpoint. Null only from a server without recovery.
 */
@Serializable
public data class GuestSessionDto(
    public val playerId: String,
    public val accessToken: String,
    public val refreshToken: String,
    public val accessTokenExpiresInSeconds: Long,
    public val recoverySecret: String? = null,
) {
    /** The session alone, as a refresh answers one: what a client stores as its session. */
    public fun session(): SessionDto =
        SessionDto(
            playerId = playerId,
            accessToken = accessToken,
            refreshToken = refreshToken,
            accessTokenExpiresInSeconds = accessTokenExpiresInSeconds,
        )
}
