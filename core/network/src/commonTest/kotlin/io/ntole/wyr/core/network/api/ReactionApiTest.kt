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
import io.ntole.wyr.core.network.jsonHeaders
import io.ntole.wyr.core.network.respondErrorDto
import io.ntole.wyr.core.network.session
import io.ntole.wyr.core.network.storeHolding
import io.ntole.wyr.core.reaction.Reaction
import io.ntole.wyr.core.reaction.ReactionRequest
import io.ntole.wyr.core.reaction.ReactionResultDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** What a reaction sends, and what its answer and a refusal come back as. */
class ReactionApiTest {
    @Test
    fun `a reaction is posted with the bearer and read back as the server counted it`() =
        runTest {
            val counted = ReactionResultDto("q1", likeCount = 3, dislikeCount = 1, myReaction = Reaction.LIKE)
            val engine = MockEngine { respond(WyrJson.encodeToString(counted), HttpStatusCode.OK, jsonHeaders) }

            val reactions = reactionApi(engine).setReaction(ReactionRequest("q1", Reaction.LIKE))

            assertEquals(counted, reactions)
            val sent = engine.requestHistory.single()
            assertEquals(HttpMethod.Post, sent.method)
            assertEquals(WyrApi.Paths.REACTIONS, sent.url.encodedPath)
            assertEquals("Bearer access-a", sent.headers[HttpHeaders.Authorization])
        }

    @Test
    fun `taking a reaction back says so in the body`() =
        runTest {
            val none = ReactionResultDto("q1", likeCount = 0, dislikeCount = 0, myReaction = Reaction.NONE)
            val engine = MockEngine { respond(WyrJson.encodeToString(none), HttpStatusCode.OK, jsonHeaders) }

            reactionApi(engine).setReaction(ReactionRequest("q1", Reaction.NONE))

            // Written out even as NONE: the server refuses a body without it, since a reaction sets
            // rather than toggles and so must say which.
            val sent = engine.requestHistory.single()
            assertEquals("""{"questionId":"q1","reaction":"NONE"}""", sent.body.toByteArray().decodeToString())
        }

    @Test
    fun `a refused reaction throws the server's code`() =
        runTest {
            val engine = MockEngine { respondErrorDto(HttpStatusCode.NotFound, ErrorCode.QUESTION_NOT_FOUND) }
            val request = ReactionRequest("gone", Reaction.DISLIKE)

            val failure = assertFailsWith<ApiException> { reactionApi(engine).setReaction(request) }

            assertEquals(ErrorCode.QUESTION_NOT_FOUND, failure.code)
            assertEquals(404, failure.status)
        }

    private fun reactionApi(engine: MockEngine): ReactionApi =
        ReactionApi(WyrHttpClient.create(BASE_URL, storeHolding(session("a")), engine))
}
