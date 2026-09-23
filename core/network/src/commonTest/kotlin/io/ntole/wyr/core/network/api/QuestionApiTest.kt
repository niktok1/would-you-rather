package io.ntole.wyr.core.network.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.network.ApiException
import io.ntole.wyr.core.network.BASE_URL
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.WyrJson
import io.ntole.wyr.core.network.respondErrorDto
import io.ntole.wyr.core.network.session
import io.ntole.wyr.core.network.storeHolding
import io.ntole.wyr.core.question.SkipRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** What a skip sends, and that its empty answer is enough. */
class QuestionApiTest {
    @Test
    fun `a skip is posted as the question with the bearer and needs nothing back`() =
        runTest {
            val engine = MockEngine { respond("", HttpStatusCode.NoContent) }

            questionApi(engine).skip(SkipRequest("q1"))

            val request = engine.requestHistory.single()
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(WyrApi.Paths.SKIPS, request.url.encodedPath)
            assertEquals("Bearer access-a", request.headers[HttpHeaders.Authorization])
            assertEquals(SkipRequest("q1"), WyrJson.decodeFromString(request.body.toByteArray().decodeToString()))
        }

    @Test
    fun `a refused skip throws the server's code though no body is read`() =
        runTest {
            val engine = MockEngine { respondErrorDto(HttpStatusCode.NotFound, ErrorCode.QUESTION_NOT_FOUND) }

            val failure = assertFailsWith<ApiException> { questionApi(engine).skip(SkipRequest("gone")) }

            assertEquals(ErrorCode.QUESTION_NOT_FOUND, failure.code)
            assertEquals(404, failure.status)
        }

    private fun questionApi(engine: MockEngine): QuestionApi =
        QuestionApi(WyrHttpClient.create(BASE_URL, storeHolding(session("a")), engine))
}
