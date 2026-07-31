package io.ntole.wyr.server.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerConfigTest {
    @Test
    fun `render style postgres url is translated to jdbc with credentials split out`() {
        // Render and Heroku hand out postgres:// URLs, which the JDBC driver refuses outright.
        val parsed =
            ServerConfig.parseDatabaseUrl(
                "postgres://wyr_user:s3cret@dpg-abc123-a.frankfurt-postgres.render.com:5432/wyr_db",
            )

        assertEquals(
            "jdbc:postgresql://dpg-abc123-a.frankfurt-postgres.render.com:5432/wyr_db",
            parsed.jdbcUrl,
        )
        assertEquals("wyr_user", parsed.user)
        assertEquals("s3cret", parsed.password)
    }

    @Test
    fun `an explicit jdbc url is passed through untouched`() {
        val parsed = ServerConfig.parseDatabaseUrl("jdbc:postgresql://localhost:5432/wyr")

        assertEquals("jdbc:postgresql://localhost:5432/wyr", parsed.jdbcUrl)
        assertNull(parsed.user)
        assertNull(parsed.password)
    }

    @Test
    fun `an empty environment yields a runnable but clearly insecure local config`() {
        val config = ServerConfig.fromEnvironment { null }

        assertEquals(8080, config.port)
        assertTrue(config.isEphemeralDatabase)
        assertTrue(config.usesDevJwtSecret)
        assertTrue(config.allowedWebOrigins.isEmpty())
    }

    @Test
    fun `environment values win over defaults`() {
        val env =
            mapOf(
                "PORT" to "9999",
                "JWT_SECRET" to "real-secret",
                "DATABASE_URL" to "postgres://u:p@db.example.com/wyr",
                "ALLOWED_WEB_ORIGINS" to "wyr.example.com, localhost:8080",
            )

        val config = ServerConfig.fromEnvironment(env::get)

        assertEquals(9999, config.port)
        assertFalse(config.usesDevJwtSecret)
        assertFalse(config.isEphemeralDatabase)
        assertEquals(listOf("wyr.example.com", "localhost:8080"), config.allowedWebOrigins)
    }
}
