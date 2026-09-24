package io.ntole.wyr.core.network.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.like.LikeRequest
import io.ntole.wyr.core.like.LikeResultDto
import io.ntole.wyr.core.network.ApiException
import io.ntole.wyr.core.network.BASE_URL
import io.ntole.wyr.core.network.WyrHttpClient
import io.ntole.wyr.core.network.WyrJson
import io.ntole.wyr.core.network.jsonHeaders
import io.ntole.wyr.core.network.respondErrorDto
import io.ntole.wyr.core.network.session
import io.ntole.wyr.core.network.storeHolding
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** What a like sends, and what its answer and a refusal come back as. */
class LikeApiTest {
    @Test
    fun `a like is posted with the bearer and read back as the server counted it`() =
        runTest {
            val counted = LikeResultDto(questionId = "q1", likeCount = 3, likedByMe = true)
            val engine = MockEngine { respond(WyrJson.encodeToString(counted), HttpStatusCode.OK, jsonHeaders) }

            val likes = likeApi(engine).setLiked(LikeRequest(questionId = "q1", liked = true))

            assertEquals(counted, likes)
            val sent = engine.requestHistory.single()
            assertEquals(HttpMethod.Post, sent.method)
            assertEquals(WyrApi.Paths.LIKES, sent.url.encodedPath)
            assertEquals("Bearer access-a", sent.headers[HttpHeaders.Authorization])
        }

    @Test
    fun `an unlike says so in the body`() =
        runTest {
            val engine =
                MockEngine {
                    respond(WyrJson.encodeToString(LikeResultDto("q1", 0, false)), HttpStatusCode.OK, jsonHeaders)
                }

            likeApi(engine).setLiked(LikeRequest(questionId = "q1", liked = false))

            // Written out even though false: the server refuses a body without it, since a like sets
            // rather than toggles and so must say which.
            val sent = engine.requestHistory.single()
            assertEquals("""{"questionId":"q1","liked":false}""", sent.body.toByteArray().decodeToString())
        }

    @Test
    fun `a refused like throws the server's code`() =
        runTest {
            val engine = MockEngine { respondErrorDto(HttpStatusCode.NotFound, ErrorCode.QUESTION_NOT_FOUND) }
            val request = LikeRequest(questionId = "gone", liked = true)

            val failure = assertFailsWith<ApiException> { likeApi(engine).setLiked(request) }

            assertEquals(ErrorCode.QUESTION_NOT_FOUND, failure.code)
            assertEquals(404, failure.status)
        }

    private fun likeApi(engine: MockEngine): LikeApi =
        LikeApi(WyrHttpClient.create(BASE_URL, storeHolding(session("a")), engine))
}
