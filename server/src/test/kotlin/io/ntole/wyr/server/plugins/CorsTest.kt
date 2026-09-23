package io.ntole.wyr.server.plugins

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.server.auth.TokenService
import io.ntole.wyr.server.config.ServerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CorsTest {
    @Test
    fun `web origins given with and without a scheme boot the server and are honoured`() =
        testApplication {
            // An entry with a scheme used to crash the boot inside Ktor's allowHost.
            val config =
                ServerConfig.fromEnvironment(
                    mapOf(
                        "ALLOWED_WEB_ORIGINS" to
                            "https://app.example.com, dev.example.com:8080, https://*.preview.test",
                    )::get,
                )

            application {
                installPlugins(config, TokenService(config))
                routing { get(WyrApi.Paths.HEALTH) { call.respondText("ok") } }
            }

            suspend fun allowedOrigin(origin: String): String? =
                client
                    .get(WyrApi.Paths.HEALTH) { header(HttpHeaders.Origin, origin) }
                    .headers[HttpHeaders.AccessControlAllowOrigin]

            assertEquals("https://app.example.com", allowedOrigin("https://app.example.com"))
            // The scheme is part of the origin: naming https must not also let plain http in.
            assertNull(allowedOrigin("http://app.example.com"))

            // A bare host keeps Ktor's default schemes.
            assertEquals("http://dev.example.com:8080", allowedOrigin("http://dev.example.com:8080"))
            assertEquals("https://dev.example.com:8080", allowedOrigin("https://dev.example.com:8080"))

            // A wildcard label keeps its scheme restriction, unlike a bare *.
            assertEquals("https://pr-7.preview.test", allowedOrigin("https://pr-7.preview.test"))
            assertNull(allowedOrigin("http://pr-7.preview.test"))
        }
}
