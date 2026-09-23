package io.ntole.wyr.server.db

import io.ktor.server.testing.testApplication
import io.ntole.wyr.server.config.ServerConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DatabaseFactoryTest {
    @Test
    fun `stopping the application closes the connection pool`() {
        lateinit var database: Database

        testApplication {
            application { database = DatabaseFactory.init(config, monitor) }
            startApplication()

            assertTrue(transaction(database) { Questions.selectAll().count() } > 0, "pool should serve while up")
        }

        // The in-memory database outlives the pool (DB_CLOSE_DELAY=-1), so the only thing that can
        // refuse this query now is a closed pool.
        val failure = assertFailsWith<SQLException> { transaction(database) { Questions.selectAll().count() } }
        assertContains(failure.message.orEmpty(), "has been closed")
    }

    private val config =
        ServerConfig(
            port = 0,
            jdbcUrl = "jdbc:h2:mem:wyr-test-pool-close;DB_CLOSE_DELAY=-1",
            dbUser = null,
            dbPassword = null,
            jwtSecret = "test-secret",
            jwtIssuer = "wyr-test",
            jwtAudience = "wyr-test-client",
            accessTokenTtlSeconds = 300,
            refreshTokenTtlSeconds = 3_600,
            allowedWebOrigins = emptyList(),
        )
}
