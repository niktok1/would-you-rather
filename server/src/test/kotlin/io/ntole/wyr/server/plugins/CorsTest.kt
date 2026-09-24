package io.ntole.wyr.server.plugins

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.server.NO_PRACTICAL_LIMIT
import io.ntole.wyr.server.auth.TokenService
import io.ntole.wyr.server.config.RequestBudget
import io.ntole.wyr.server.config.ServerConfig
import io.ntole.wyr.server.testDatabaseFor
import io.ntole.wyr.server.wyrModule
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

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

    @Test
    fun `a browser on an allowed origin may send the admin token`() =
        testApplication {
            val config = ServerConfig.fromEnvironment(mapOf("ALLOWED_WEB_ORIGINS" to "https://app.example.com")::get)

            application {
                installPlugins(config, TokenService(config))
                routing { post(WyrApi.Paths.ADMIN_APPROVALS) { call.respondText("ok") } }
            }

            // A header of the app's own, so the browser asks first; refused, a moderator on the web
            // client could never reach an admin route.
            val preflight =
                client.options(WyrApi.Paths.ADMIN_APPROVALS) {
                    header(HttpHeaders.Origin, "https://app.example.com")
                    header(HttpHeaders.AccessControlRequestMethod, "POST")
                    header(HttpHeaders.AccessControlRequestHeaders, "${WyrApi.Headers.ADMIN_TOKEN}, content-type")
                }

            assertEquals(HttpStatusCode.OK, preflight.status)
            assertContains(
                preflight.headers[HttpHeaders.AccessControlAllowHeaders].orEmpty().lowercase(),
                WyrApi.Headers.ADMIN_TOKEN.lowercase(),
            )
        }

    @Test
    fun `a browser on an allowed origin may read how long a 429 asks it to wait`() =
        testApplication {
            val origin = "https://app.example.com"
            val adminToken = "test-admin-token-0123456789abcdef"
            val database = testDatabaseFor("cors-retry-after")
            val config =
                ServerConfig
                    .fromEnvironment(mapOf("ALLOWED_WEB_ORIGINS" to origin, "ADMIN_TOKEN" to adminToken)::get)
                    .copy(
                        jdbcUrl = database.jdbcUrl,
                        dbUser = database.user,
                        dbPassword = database.password,
                        rateLimits = NO_PRACTICAL_LIMIT.copy(admin = RequestBudget(requests = 1, per = 1.minutes)),
                    )

            application { wyrModule(config) }

            suspend fun queue(): HttpResponse =
                client.get(WyrApi.Paths.ADMIN_SUBMISSIONS) {
                    header(HttpHeaders.Origin, origin)
                    header(WyrApi.Headers.ADMIN_TOKEN, adminToken)
                }

            assertEquals(HttpStatusCode.OK, queue().status)
            val refused = queue()

            // A script reads only the safelisted headers and those the response names, and Retry-After is
            // not safelisted: unnamed, the moderation app's page could never say how long to wait.
            assertEquals(HttpStatusCode.TooManyRequests, refused.status)
            assertNotNull(refused.headers[HttpHeaders.RetryAfter])
            assertEquals(origin, refused.headers[HttpHeaders.AccessControlAllowOrigin])
            assertContains(
                refused.headers[HttpHeaders.AccessControlExposeHeaders].orEmpty().lowercase(),
                HttpHeaders.RetryAfter.lowercase(),
            )
        }
}
