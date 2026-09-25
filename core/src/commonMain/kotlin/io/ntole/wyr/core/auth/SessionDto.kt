package io.ntole.wyr.core.auth

import kotlinx.serialization.Serializable

/**
 * A newly minted, refreshed or recovered player session: one device's (CLAUDE.md §8a, *Sessions*).
 *
 * Returned by the refresh and recovery endpoints, and by the guest-session one as the fields of a
 * [GuestSessionDto]. The session is **server-issued**: the client never invents its own identity,
 * so [playerId] and the points attributed to it cannot be forged by a tampered client the way a
 * client-supplied device id could be.
 *
 * [accessToken] is a short-lived signed JWT sent as `Authorization: Bearer`.
 * [refreshToken] is an opaque long-lived credential — the server stores only its hash — and is
 * the only thing that must survive being written to platform storage.
 */
@Serializable
public data class SessionDto(
    public val playerId: String,
    public val accessToken: String,
    public val refreshToken: String,
    public val accessTokenExpiresInSeconds: Long,
)
