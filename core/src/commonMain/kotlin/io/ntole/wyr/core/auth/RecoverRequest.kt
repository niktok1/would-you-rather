package io.ntole.wyr.core.auth

import kotlinx.serialization.Serializable

/**
 * Open a new session for the player whose recovery secret [recoverySecret] is (CLAUDE.md §8a,
 * *Recovery*), as a phone that replaced the one the player played on does.
 *
 * Sent unauthenticated: the secret is the credential, as the refresh token is for [RefreshRequest],
 * and travels in the body for the same reason.
 */
@Serializable
public data class RecoverRequest(
    public val recoverySecret: String,
) {
    /** Never the secret itself (see [redacted]). */
    override fun toString(): String = "RecoverRequest(recoverySecret=${redacted(recoverySecret)})"
}
