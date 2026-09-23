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
import io.ntole.wyr.server.player.playerRoutes
import io.ntole.wyr.server.plugins.installPlugins
import io.ntole.wyr.server.question.questionRoutes
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

    installPlugins(config, tokens)

    routing {
        // Render pings this to decide whether the service is live.
        get(WyrApi.Paths.HEALTH) {
            call.respond(mapOf("status" to "ok"))
        }

        authRoutes(db, tokens, config)
        questionRoutes(db)
        voteRoutes(db)
        playerRoutes(db)
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
    if (config.allowedWebOrigins.isEmpty()) {
        log.info("ALLOWED_WEB_ORIGINS is unset — browser clients will be blocked by CORS.")
    }
}
