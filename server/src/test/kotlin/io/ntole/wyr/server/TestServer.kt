package io.ntole.wyr.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.server.config.ServerConfig
import io.ntole.wyr.server.db.TEST_SEEDS
import kotlinx.serialization.json.Json

/** The admin token a [runTestServer] server is moderated with. */
internal const val FLOW_ADMIN_TOKEN = "flow-admin-token-0123456789abcdef"

/**
 * The whole server, on its own database [name] names, seeded with the first seeds alone
 * ([TEST_SEEDS]) and moderated with [FLOW_ADMIN_TOKEN], with budgets no test comes near, and
 * whatever [configure] changes. [block] gets a client that decodes the contract and the database, for
 * a test that reaches into it.
 */
internal fun runTestServer(
    name: String,
    configure: (ServerConfig) -> ServerConfig = { it },
    block: suspend ApplicationTestBuilder.(client: HttpClient, database: TestDatabaseSettings) -> Unit,
) = testApplication {
    val database = testDatabaseFor(name)
    val config =
        configure(
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
                adminToken = FLOW_ADMIN_TOKEN,
                rateLimits = NO_PRACTICAL_LIMIT,
                clientIpHeader = null,
                onRender = false,
            ),
        )

    application { wyrModule(config, TEST_SEEDS) }

    val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    block(client, database)
}

/** A fresh guest's session. */
internal suspend fun HttpClient.mintGuest(): SessionDto = post(WyrApi.Paths.AUTH_GUEST).body()
