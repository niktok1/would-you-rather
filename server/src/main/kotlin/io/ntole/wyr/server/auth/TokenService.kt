package io.ntole.wyr.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import io.ntole.wyr.server.config.ServerConfig
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Date

/**
 * Issues and validates player credentials.
 *
 * Two credentials with different jobs:
 *  - a short-lived signed **access token**, stateless, checked on every request;
 *  - a long-lived opaque **refresh token**, of which only a SHA-256 hash is stored, so a
 *    database leak does not hand out working sessions.
 *
 * Refresh tokens are rotated on every use: a refreshed session retires the token that produced it,
 * which works once more, until the next rotation displaces it (the grace, CLAUDE.md §8a, which
 * `ServerConfig.refreshGraceSeconds` can bound in time), and then never again. So an old token replayed
 * is a dead end once a later rotation has displaced it; one replayed before then works, which is the
 * grace's cost (CLAUDE.md §8a, *The cost*).
 */
class TokenService(
    private val config: ServerConfig,
) {
    private val algorithm: Algorithm = Algorithm.HMAC256(config.jwtSecret)

    val verifier: JWTVerifier =
        JWT
            .require(algorithm)
            .withIssuer(config.jwtIssuer)
            .withAudience(config.jwtAudience)
            .build()

    /**
     * [verifier], but taking a token up to a refresh token's lifetime past its expiry. Only for telling
     * whose rate-limit budget a request spends ([verifiedPlayerId]), never for letting one in: every
     * player's token expires in its turn, and the request that finds it expired must spend that player's
     * budget and reach its 401, which is what the client refreshes on, rather than share its address's.
     */
    internal val expiredTokenVerifier: JWTVerifier =
        JWT
            .require(algorithm)
            .withIssuer(config.jwtIssuer)
            .withAudience(config.jwtAudience)
            .acceptExpiresAt(config.refreshTokenTtlSeconds)
            .build()

    /**
     * An access token for [playerId], naming [sessionId], the session its refresh token belongs to, so
     * a logout knows which device's session to end (`authenticatedSessionId`).
     */
    fun issueAccessToken(
        playerId: String,
        sessionId: String,
        now: Long = System.currentTimeMillis(),
    ): String =
        JWT
            .create()
            .withIssuer(config.jwtIssuer)
            .withAudience(config.jwtAudience)
            .withSubject(playerId)
            .withClaim(CLAIM_PLAYER_ID, playerId)
            .withClaim(CLAIM_SESSION_ID, sessionId)
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + config.accessTokenTtlSeconds * 1_000L))
            .sign(algorithm)

    /** A fresh opaque refresh token, plus the hash to store in its session's row. */
    fun issueRefreshToken(): Opaque = opaque()

    fun hash(token: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(token.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun opaque(): Opaque {
        val bytes = ByteArray(OPAQUE_BYTES).also(secureRandom::nextBytes)
        val value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return Opaque(value = value, hash = hash(value))
    }

    /** An opaque credential as issued, and the SHA-256 of it, the only part the server keeps. */
    data class Opaque(
        val value: String,
        val hash: String,
    )

    companion object {
        const val CLAIM_PLAYER_ID: String = "playerId"
        const val CLAIM_SESSION_ID: String = "sessionId"
        private const val OPAQUE_BYTES = 32
        private val secureRandom = SecureRandom()
    }
}
