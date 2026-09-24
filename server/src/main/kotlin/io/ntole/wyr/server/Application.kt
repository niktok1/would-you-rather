package io.ntole.wyr.server

import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.server.auth.TokenService
import io.ntole.wyr.server.auth.authRoutes
import io.ntole.wyr.server.config.ServerConfig
import io.ntole.wyr.server.db.DatabaseFactory
import io.ntole.wyr.server.db.Db
import io.ntole.wyr.server.like.likeRoutes
import io.ntole.wyr.server.moderation.AdminToken
import io.ntole.wyr.server.moderation.moderationRoutes
import io.ntole.wyr.server.player.playerRoutes
import io.ntole.wyr.server.plugins.installPlugins
import io.ntole.wyr.server.plugins.installRateLimits
import io.ntole.wyr.server.question.questionRoutes
import io.ntole.wyr.server.question.submissionRoutes
import io.ntole.wyr.server.vote.voteRoutes

fun main() {
    val config = ServerConfig.fromEnvironment()

    embeddedServer(
        factory = Netty,
        port = config.port,
        host = "0.0.0.0",
        module = { wyrModule(config) },
    ).start(wait = true)
}

/**
 * Assembles the application.
 *
 * Takes its [config] as a parameter rather than reading the environment itself so tests can
 * stand the whole server up against an isolated in-memory database.
 */
fun Application.wyrModule(config: ServerConfig) {
    warnAboutInsecureDefaults(config)

    val database = DatabaseFactory.init(config, monitor)
    val db = Db(database)
    val tokens = TokenService(config)

    // Built once, for the routes and for the rate limit on failed admin tokens alike.
    val adminToken = config.adminToken?.let { token -> AdminToken(token) }

    installPlugins(config, tokens)
    installRateLimits(config, tokens, adminToken)

    routing {
        // Render pings this to decide whether the service is live. In no rate-limit group, so a check
        // is never refused.
        get(WyrApi.Paths.HEALTH) {
            call.respond(mapOf("status" to "ok"))
        }

        authRoutes(db, tokens, config)
        questionRoutes(db)
        submissionRoutes(db)
        voteRoutes(db)
        likeRoutes(db)
        playerRoutes(db)
        // Not registered at all without an admin token, so moderation is off (CLAUDE.md §8d).
        moderationRoutes(db, adminToken)
    }
}

private fun Application.warnAboutInsecureDefaults(config: ServerConfig) {
    if (config.usesDevJwtSecret) {
        log.warn(
            "JWT_SECRET is unset — using the built-in development secret. Every issued token is " +
                "forgeable by anyone with the source. Set JWT_SECRET before exposing this.",
        )
    }
    if (config.isEphemeralDatabase) {
        log.warn(
            "DATABASE_URL is unset — running on in-memory H2. All players, votes, and points are " +
                "discarded on shutdown.",
        )
    }
    if (config.onRender && config.clientIpHeader == null) {
        log.warn(
            "CLIENT_IP_HEADER is unset on Render, so every request's address is Render's proxy and every " +
                "client shares each per-address rate limit, such as ${config.rateLimits.guests.requests} new " +
                "guests an hour for everyone together. render.yaml sets it to CF-Connecting-IP.",
        )
    }
    if (config.allowedWebOrigins.isEmpty()) {
        log.info("ALLOWED_WEB_ORIGINS is unset — browser clients will be blocked by CORS.")
    }
    if (config.adminToken == null) {
        log.warn(
            "ADMIN_TOKEN is unset — moderation is off. The admin routes are not served, so no " +
                "submission can be approved or rejected and every one stays pending.",
        )
    } else if (config.usesShortAdminToken) {
        log.warn(
            "ADMIN_TOKEN is shorter than ${ServerConfig.MIN_ADMIN_TOKEN_LENGTH} characters. Whoever " +
                "guesses it can approve and reject every submission, and guesses are limited only per " +
                "address, ${config.rateLimits.adminTokenFailures.requests} a minute, so a caller with many " +
                "addresses gets many more. Use a random one, such as the output of openssl rand -hex 32.",
        )
    }
}
