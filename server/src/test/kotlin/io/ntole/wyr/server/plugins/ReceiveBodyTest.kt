package io.ntole.wyr.server.plugins

import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.JsonConvertException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.core.vote.VoteRequest
import io.ntole.wyr.server.auth.TokenService
import io.ntole.wyr.server.config.ServerConfig
import kotlinx.serialization.SerializationException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

class ReceiveBodyTest {
    @Test
    fun `a failure while receiving that is not a parse failure propagates as a 500`() =
        testApplication {
            // Thrown from its own receive hook, outside the converter, so content negotiation
            // does not wrap it. A helper that swallowed everything would answer 400 here.
            val failingReceive =
                createApplicationPlugin("FailingReceive") {
                    onCallReceive { _ -> throw IllegalStateException("receive pipeline broke") }
                }
            val config = ServerConfig.fromEnvironment { null }

            application {
                installPlugins(config, TokenService(config))
                install(failingReceive)
                routing {
                    post("/probe") {
                        call.receiveOrReject<VoteRequest>("probe")
                        call.respondText("ok")
                    }
                }
            }

            val client = createClient { install(ClientContentNegotiation) { json(ServerJson) } }
            val response =
                client.post("/probe") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"questionId":"seed-1","choice":"A"}""")
                }

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals(ErrorCode.INTERNAL, response.body<ErrorDto>().code)
        }

    @Test
    fun `only a body or content type the client got wrong becomes a validation failure`() {
        fun rejection(cause: Throwable?): Throwable =
            rejectionFor(BadRequestException("Failed to convert request body", cause), "probe")

        listOf(
            JsonConvertException("Illegal input", SerializationException("unexpected token")),
            BadContentTypeFormatException("application/"),
            null,
        ).forEach { cause ->
            val failure = assertIs<ApiFailure>(rejection(cause), "cause $cause")
            assertEquals(ErrorCode.VALIDATION_FAILED, failure.code)
        }

        // Ktor wraps these in BadRequestException too; they are server faults, not bad input.
        val lostConnection = IOException("connection reset while reading the body")
        assertSame(lostConnection, rejection(lostConnection))

        val outOfMemory = OutOfMemoryError("simulated")
        assertSame(outOfMemory, rejection(outOfMemory))
        assertSame(outOfMemory, rejection(JsonConvertException("Illegal input", outOfMemory)))
    }
}
