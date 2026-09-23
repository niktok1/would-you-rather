package io.ntole.wyr.core.data.mapper

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.data.BASE_URL
import io.ntole.wyr.core.data.respondErrorDto
import io.ntole.wyr.core.data.session
import io.ntole.wyr.core.data.storeHolding
import io.ntole.wyr.core.domain.error.DomainError
import io.ntole.wyr.core.domain.error.WyrException
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.api.QuestionApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * runApi against what the real client configuration actually throws, rather than hand-built
 * exceptions — the gap between the two is where every failure used to collapse into UNKNOWN.
 */
class RunApiOverHttpTest {
    @Test
    fun `a request that never reached the server is NETWORK`() =
        runTest {
            val offline = MockEngine { throw ConnectTimeoutException("connect timed out") }

            assertEquals(DomainError.NETWORK, errorFrom(offline))
        }

    @Test
    fun `a proxy's HTML 503 page is SERVER`() =
        runTest {
            val proxyPage =
                MockEngine {
                    respond(
                        "<html><body>503 Service Unavailable</body></html>",
                        HttpStatusCode.ServiceUnavailable,
                        headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()),
                    )
                }

            assertEquals(DomainError.SERVER, errorFrom(proxyPage))
        }

    @Test
    fun `a dead refresh token is UNAUTHORIZED`() =
        runTest {
            // This is the code that triggers session recovery; it used to arrive as UNKNOWN.
            val deadSession =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        WyrApi.Paths.AUTH_REFRESH -> {
                            respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.INVALID_REFRESH_TOKEN)
                        }

                        else -> {
                            respondErrorDto(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED)
                        }
                    }
                }

            assertEquals(DomainError.UNAUTHORIZED, errorFrom(deadSession))
        }

    private suspend fun errorFrom(engine: MockEngine): DomainError {
        val api = QuestionApi(WyrHttpClient.create(BASE_URL, storeHolding(session("a")), engine))
        return assertFailsWith<WyrException> { runApi { api.page() } }.error
    }
}
