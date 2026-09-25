package io.ntole.wyr.core.domain.session

/**
 * A read-only look at the stored session, for diagnostics such as the dev console.
 *
 * Separate from [SessionRepository] on purpose: whoever holds this can see the session but not
 * change it, and nothing here reaches the network.
 */
public interface SessionDiagnostics {
    /** The stored session, or `null` when there is none. */
    public suspend fun info(): SessionInfo?
}

/**
 * What [SessionDiagnostics] reports. Never the credentials themselves.
 *
 * [accessTokenExpiresAtEpochMillis] is `null` when the access token cannot be read, which is a
 * reason to look closer rather than a failure.
 */
public data class SessionInfo(
    public val playerId: String,
    public val accessTokenExpiresAtEpochMillis: Long?,
)
