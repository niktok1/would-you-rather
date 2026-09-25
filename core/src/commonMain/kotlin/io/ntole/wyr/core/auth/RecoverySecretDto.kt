package io.ntole.wyr.core.auth

import kotlinx.serialization.Serializable

/**
 * A new recovery secret for the session player (CLAUDE.md §8a, *Recovery*), as a
 * [GuestSessionDto.recoverySecret] is. It replaces the player's last one, which recovers nobody from
 * then on; the sessions that one opened live on.
 */
@Serializable
public data class RecoverySecretDto(
    public val recoverySecret: String,
) {
    /** Never the secret itself (see [redacted]). */
    override fun toString(): String = "RecoverySecretDto(recoverySecret=${redacted(recoverySecret)})"
}
