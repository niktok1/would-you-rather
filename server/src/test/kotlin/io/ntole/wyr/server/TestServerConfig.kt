package io.ntole.wyr.server

import io.ntole.wyr.server.config.RateLimits
import io.ntole.wyr.server.config.ServerConfig

/**
 * The configuration a flow test's server runs on [database] with: the test secret, issuer and audience
 * the tests sign their own tokens with, moderated with [adminToken], and budgets no test's traffic
 * comes near unless [rateLimits] says otherwise. Every optional feature is off, as with nothing set.
 */
internal fun testServerConfig(
    database: TestDatabaseSettings,
    adminToken: String? = null,
    rateLimits: RateLimits = NO_PRACTICAL_LIMIT,
): ServerConfig =
    ServerConfig(
        port = 0,
        jdbcUrl = database.jdbcUrl,
        dbUser = database.user,
        dbPassword = database.password,
        jwtSecret = "test-secret",
        jwtIssuer = "wyr-test",
        jwtAudience = "wyr-test-client",
        accessTokenTtlSeconds = 300,
        refreshTokenTtlSeconds = 3_600,
        refreshGraceSeconds = null,
        allowedWebOrigins = emptyList(),
        adminToken = adminToken,
        rateLimits = rateLimits,
        clientIpHeader = null,
        onRender = false,
    )
