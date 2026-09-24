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
import io.ntole.wyr.core.network.respondEmptyPage
import io.ntole.wyr.core.network.respondErrorDto
import io.ntole.wyr.core.network.session
import io.ntole.wyr.core.network.storeHolding
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.SkipRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** What a feed request asks for, what a skip sends, and that its empty answer is enough. */
class QuestionApiTest {
    @Test
    fun `a feed of several categories asks for each once in declaration order`() =
        runTest {
            val engine = MockEngine { respondEmptyPage() }
            val categories = setOf(QuestionCategory.RANDOM, QuestionCategory.FOOD, QuestionCategory.ETHICS)

            questionApi(engine).page(categories = categories)

            // Not the order the set was built in: one selection is one request.
            val url = engine.requestHistory.single().url
            assertEquals(listOf("FOOD", "ETHICS", "RANDOM"), url.parameters.getAll(WyrApi.Query.CATEGORY))
        }

    @Test
    fun `a feed of no categories asks for every category`() =
        runTest {
            val engine = MockEngine { respondEmptyPage() }

            questionApi(engine).page()

            val url = engine.requestHistory.single().url
            assertEquals(null, url.parameters.getAll(WyrApi.Query.CATEGORY))
        }

    @Test
    fun `UNKNOWN is never asked for`() =
        runTest {
            val engine = MockEngine { respondEmptyPage() }

            questionApi(engine).page(categories = setOf(QuestionCategory.UNKNOWN, QuestionCategory.SUPERPOWERS))

            // The server refuses it: it is what this build cannot read, not a category to ask for.
            val url = engine.requestHistory.single().url
            assertEquals(listOf("SUPERPOWERS"), url.parameters.getAll(WyrApi.Query.CATEGORY))
        }

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
