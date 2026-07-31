package io.ntole.wyr.core.auth

import kotlinx.serialization.Serializable

/**
 * Exchange a refresh token for a fresh [SessionDto].
 *
 * Sent unauthenticated — the refresh token *is* the credential, which is why it travels in the
 * body rather than in an `Authorization` header.
 */
@Serializable
public data class RefreshRequest(
    public val refreshToken: String,
)
