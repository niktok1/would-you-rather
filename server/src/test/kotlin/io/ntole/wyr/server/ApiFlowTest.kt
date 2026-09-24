package io.ntole.wyr.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.RefreshRequest
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.core.player.PlayerStatsDto
import io.ntole.wyr.core.question.ApproveSubmissionRequest
import io.ntole.wyr.core.question.QuestionCategory
import io.ntole.wyr.core.question.QuestionDto
import io.ntole.wyr.core.question.QuestionPageDto
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.RejectSubmissionRequest
import io.ntole.wyr.core.question.SkipRequest
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.question.SubmissionListDto
import io.ntole.wyr.core.question.SubmitQuestionRequest
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteRequest
import io.ntole.wyr.core.vote.VoteResultDto
import io.ntole.wyr.core.vote.VoteTallyDto
import io.ntole.wyr.server.auth.TokenService
import io.ntole.wyr.server.config.ServerConfig
import io.ntole.wyr.server.vote.Scoring
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * End-to-end coverage of the vertical slice: zero-click session, the question feed, vote, reveal.
 *
 * Each test gets its own in-memory database, keyed by name, so vote counts from one test cannot
 * bleed into another's tally assertions.
 */
class ApiFlowTest {
    @Test
    fun `guest session, question fetch, and vote all work with no player interaction`() =
        runServer("happy-path") { client ->
            val session: SessionDto = client.post(WyrApi.Paths.AUTH_GUEST).body()
            assertTrue(session.playerId.isNotBlank())
            assertTrue(session.accessToken.isNotBlank())
            assertTrue(session.refreshToken.isNotBlank())

            val batch = client.batch(session)
            assertTrue(batch.isNotEmpty(), "seed questions should be served")

            val question = batch.first()
            val result: VoteResultDto =
                client
                    .post(WyrApi.Paths.VOTES) {
                        bearerAuth(session.accessToken)
                        contentType(ContentType.Application.Json)
                        setBody(VoteRequest(questionId = question.id, choice = OptionSide.A, attemptId = "attempt-1"))
                    }.body()

            assertEquals(question.id, result.questionId)
            assertEquals(OptionSide.A, result.yourChoice)
            // The voter's own vote is included in the tally they are shown.
            assertEquals(1L, result.tally.votesA)
            assertEquals(0L, result.tally.votesB)
            // A first answer earns the flat point, and that point is the whole running total.
            assertEquals(Scoring.POINTS_PER_ANSWER, result.pointsAwarded)
            assertEquals(Scoring.POINTS_PER_ANSWER, result.totalPoints)
            assertFalse(result.replayed)
        }

    @Test
    fun `every answer pays one point whichever side it picks, and the total accumulates`() =
        runServer("flat-scoring") { client ->
            val player = client.guest()
            val (opened, contested) = client.batch(player)

            // A tally holds one vote per player, so a real minority takes two other players on
            // the majority side first. Their majority picks pay the same flat point.
            repeat(times = 2) {
                val other: SessionDto = client.post(WyrApi.Paths.AUTH_GUEST).body()
                assertEquals(Scoring.POINTS_PER_ANSWER, client.vote(other, contested.id, OptionSide.A).pointsAwarded)
            }

            val majority = client.vote(player, opened.id, OptionSide.A)
            val minority = client.vote(player, contested.id, OptionSide.B)
            assertEquals(VoteTallyDto(votesA = 1, votesB = 0), majority.tally)
            assertEquals(VoteTallyDto(votesA = 2, votesB = 1), minority.tally)

            assertEquals(Scoring.POINTS_PER_ANSWER, majority.pointsAwarded)
            assertEquals(Scoring.POINTS_PER_ANSWER, minority.pointsAwarded)
            assertEquals(2 * Scoring.POINTS_PER_ANSWER, minority.totalPoints)
        }

    @Test
    fun `answering again pays again and moves the player's one vote`() =
        runServer("re-answer") { client ->
            val player = client.guest()
            val other = client.guest()
            val question = client.batch(player).first()
            client.vote(other, question.id, OptionSide.A)

            val first = client.vote(player, question.id, OptionSide.A)
            val again = client.vote(player, question.id, OptionSide.B)

            assertEquals(VoteTallyDto(votesA = 2, votesB = 0), first.tally)
            assertEquals(OptionSide.B, again.yourChoice, "the pick may change")
            assertEquals(
                VoteTallyDto(votesA = 1, votesB = 1),
                again.tally,
                "the player's vote moved rather than doubled",
            )
            assertEquals(Scoring.POINTS_PER_ANSWER, again.pointsAwarded)
            assertEquals(2 * Scoring.POINTS_PER_ANSWER, again.totalPoints)
            assertFalse(again.replayed, "a new attempt is a fresh answer")
        }

    @Test
    fun `repeating an attempt replays its result and pays nothing`() =
        runServer("replay") { client ->
            val player = client.guest()
            val question = client.batch(player).first()
            val answered = client.vote(player, question.id, OptionSide.A, attemptId = "attempt-1")

            // A retry of that answer, even one that asks for the other side this time.
            val replay = client.vote(player, question.id, OptionSide.B, attemptId = "attempt-1")

            assertTrue(replay.replayed)
            assertEquals(0, replay.pointsAwarded)
            assertEquals(answered.totalPoints, replay.totalPoints)
            assertEquals(OptionSide.A, replay.yourChoice, "the stored side, not the repeat's")
            assertEquals(answered.tally, replay.tally)

            // A new attempt is an answer again, and from then on it is the one a retry replays.
            val switched = client.vote(player, question.id, OptionSide.B, attemptId = "attempt-2")
            assertEquals(Scoring.POINTS_PER_ANSWER, switched.pointsAwarded)
            val replayOfSwitch = client.vote(player, question.id, OptionSide.A, attemptId = "attempt-2")
            assertTrue(replayOfSwitch.replayed)
            assertEquals(OptionSide.B, replayOfSwitch.yourChoice)
            assertEquals(VoteTallyDto(votesA = 0, votesB = 1), replayOfSwitch.tally)
            assertEquals(switched.totalPoints, replayOfSwitch.totalPoints)
        }

    @Test
    fun `an attempt id that is blank or too long is a validation error`() =
        runServer("bad-attempt") { client ->
            val player = client.guest()
            val longest = "x".repeat(WyrApi.Limits.MAX_ATTEMPT_ID_LENGTH)

            listOf("empty" to "", "blank" to "   ", "too long" to longest + "x").forEach { (case, attemptId) ->
                val response = client.castVote(player, VoteRequest("seed-1", OptionSide.A, attemptId))
                assertEquals(HttpStatusCode.BadRequest, response.status, case)
                assertEquals(ErrorCode.VALIDATION_FAILED, response.body<ErrorDto>().code, case)
            }

            // The limit itself is allowed, so the column has room for it.
            assertEquals(
                HttpStatusCode.OK,
                client.castVote(player, VoteRequest("seed-1", OptionSide.A, longest)).status,
            )
        }

    @Test
    fun `an id with a control character in it is a validation error, not a database failure`() =
        runServer("control-character") { client ->
            val player = client.guest()

            listOf(
                // PostgreSQL refuses a NUL in text, which H2 stores, so only this check can catch it.
                "NUL in questionId" to VoteRequest("seed-1\u0000", OptionSide.A, "attempt-1"),
                "NUL in attemptId" to VoteRequest("seed-1", OptionSide.A, "attempt\u00001"),
                "newline in attemptId" to VoteRequest("seed-1", OptionSide.A, "attempt\n1"),
            ).forEach { (case, request) ->
                val response = client.castVote(player, request)
                assertEquals(HttpStatusCode.BadRequest, response.status, case)
                assertEquals(ErrorCode.VALIDATION_FAILED, response.body<ErrorDto>().code, case)
            }
        }

    @Test
    fun `voting without a token is unauthorized and reports why`() =
        runServer("no-token") { client ->
            val question = client.batch(client.guest()).first()

            val response =
                client.post(WyrApi.Paths.VOTES) {
                    contentType(ContentType.Application.Json)
                    setBody(VoteRequest(question.id, OptionSide.A, attemptId = "attempt-1"))
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ErrorCode.UNAUTHORIZED, response.body<ErrorDto>().code)
        }

    @Test
    fun `refresh rotates the token and the old one stops working`() =
        runServer("refresh-rotation") { client ->
            val original: SessionDto = client.post(WyrApi.Paths.AUTH_GUEST).body()

            val refreshed: SessionDto =
                client
                    .post(WyrApi.Paths.AUTH_REFRESH) {
                        contentType(ContentType.Application.Json)
                        setBody(RefreshRequest(original.refreshToken))
                    }.body()

            assertEquals(original.playerId, refreshed.playerId, "refresh must not change identity")
            assertNotEquals(original.refreshToken, refreshed.refreshToken)

            // Replaying the consumed token must fail — that is the point of rotating it.
            val replay =
                client.post(WyrApi.Paths.AUTH_REFRESH) {
                    contentType(ContentType.Application.Json)
                    setBody(RefreshRequest(original.refreshToken))
                }
            assertEquals(HttpStatusCode.Unauthorized, replay.status)
            assertEquals(ErrorCode.INVALID_REFRESH_TOKEN, replay.body<ErrorDto>().code)
        }

    @Test
    fun `an unknown refresh token is rejected`() =
        runServer("bad-refresh") { client ->
            val response =
                client.post(WyrApi.Paths.AUTH_REFRESH) {
                    contentType(ContentType.Application.Json)
                    setBody(RefreshRequest("not-a-real-token"))
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ErrorCode.INVALID_REFRESH_TOKEN, response.body<ErrorDto>().code)
        }

    @Test
    fun `voting on a question that does not exist is a not-found, not a crash`() =
        runServer("missing-question") { client ->
            val session: SessionDto = client.post(WyrApi.Paths.AUTH_GUEST).body()

            listOf("no-such-question", LONGER_THAN_ANY_ID).forEach { questionId ->
                val response =
                    client.post(WyrApi.Paths.VOTES) {
                        bearerAuth(session.accessToken)
                        contentType(ContentType.Application.Json)
                        setBody(VoteRequest(questionId, OptionSide.A, attemptId = "attempt-1"))
                    }

                assertEquals(HttpStatusCode.NotFound, response.status, questionId)
                assertEquals(ErrorCode.QUESTION_NOT_FOUND, response.body<ErrorDto>().code, questionId)
            }
        }

    @Test
    fun `a validly signed token for a player that does not exist is unauthorized, not a duplicate vote`() =
        runServer("ghost-player") { client ->
            val real = client.guest()
            val question = client.batch(real).first()

            // The helper must sign exactly as the server does, or the 401 below would only prove
            // a bad signature. A real player's token from it has to be accepted first.
            val accepted =
                client.post(WyrApi.Paths.VOTES) {
                    bearerAuth(signAccessToken(real.playerId))
                    contentType(ContentType.Application.Json)
                    setBody(VoteRequest(question.id, OptionSide.A, attemptId = "attempt-1"))
                }
            assertEquals(HttpStatusCode.OK, accepted.status)

            // What a client holds after an H2 dev server restarts: a token that still verifies
            // against the constant dev secret, for a player the fresh database never had.
            val response =
                client.post(WyrApi.Paths.VOTES) {
                    bearerAuth(signAccessToken("no-such-player"))
                    contentType(ContentType.Application.Json)
                    setBody(VoteRequest(question.id, OptionSide.A, attemptId = "attempt-1"))
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ErrorCode.UNAUTHORIZED, response.body<ErrorDto>().code)
        }

    @Test
    fun `a token signed with another secret is unauthorized`() =
        runServer("wrong-secret") { client ->
            val session: SessionDto = client.post(WyrApi.Paths.AUTH_GUEST).body()

            val response =
                client.post(WyrApi.Paths.VOTES) {
                    bearerAuth(signAccessToken(session.playerId, secret = "not-the-server-secret"))
                    contentType(ContentType.Application.Json)
                    setBody(VoteRequest("seed-1", OptionSide.A, attemptId = "attempt-1"))
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ErrorCode.UNAUTHORIZED, response.body<ErrorDto>().code)
        }

    @Test
    fun `a vote body the server cannot use is a validation error`() =
        runServer("malformed-vote") { client ->
            val session: SessionDto = client.post(WyrApi.Paths.AUTH_GUEST).body()

            suspend fun assertRejected(
                case: String,
                body: String,
                type: ContentType = ContentType.Application.Json,
            ) {
                val response =
                    client.post(WyrApi.Paths.VOTES) {
                        bearerAuth(session.accessToken)
                        contentType(type)
                        setBody(body)
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status, case)
                assertEquals(ErrorCode.VALIDATION_FAILED, response.body<ErrorDto>().code, case)
            }

            assertRejected("malformed json", "{not json")
            assertRejected("unknown side", """{"questionId":"seed-1","choice":"C","attemptId":"a1"}""")
            assertRejected("blank questionId", """{"questionId":"  ","choice":"A","attemptId":"a1"}""")
            assertRejected("no attemptId", """{"questionId":"seed-1","choice":"A"}""")
            assertRejected(
                "not sent as json",
                """{"questionId":"seed-1","choice":"A","attemptId":"a1"}""",
                ContentType.Text.Plain,
            )
        }

    @Test
    fun `a refresh body that does not parse is a validation error`() =
        runServer("malformed-refresh") { client ->
            val response =
                client.post(WyrApi.Paths.AUTH_REFRESH) {
                    contentType(ContentType.Application.Json)
                    setBody("{not json")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(ErrorCode.VALIDATION_FAILED, response.body<ErrorDto>().code)
        }

    @Test
    fun `a category filter naming anything but real categories is rejected rather than served as an empty batch`() =
        runServer("unknown-category") { client ->
            val player = client.guest()
            val category = WyrApi.Query.CATEGORY

            listOf(
                "UNKNOWN" to "?$category=${QuestionCategory.UNKNOWN.name}",
                "UNKNOWN beside a real one" to "?$category=FOOD&$category=${QuestionCategory.UNKNOWN.name}",
                "no category at all beside a real one" to "?$category=FOOD&$category=FROM_THE_FUTURE",
                "a comma-separated list" to "?$category=FOOD,ETHICS",
                "an empty name" to "?$category=",
            ).forEach { (case, query) ->
                val response = client.feed(player, query)

                assertEquals(HttpStatusCode.BadRequest, response.status, case)
                assertEquals(ErrorCode.VALIDATION_FAILED, response.body<ErrorDto>().code, case)
            }
        }

    @Test
    fun `the question feed needs a session`() =
        runServer("feed-no-token") { client ->
            val response = client.get(WyrApi.Paths.QUESTIONS)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ErrorCode.UNAUTHORIZED, response.body<ErrorDto>().code)
        }

    @Test
    fun `a validly signed token for a player that does not exist gets no feed`() =
        runServer("feed-ghost-player") { client ->
            // As for the ghost-player vote, the helper's token for a real player has to pass first.
            val real = client.guest()
            val accepted = client.get(WyrApi.Paths.QUESTIONS) { bearerAuth(signAccessToken(real.playerId)) }
            assertEquals(HttpStatusCode.OK, accepted.status)

            val response = client.get(WyrApi.Paths.QUESTIONS) { bearerAuth(signAccessToken("no-such-player")) }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ErrorCode.UNAUTHORIZED, response.body<ErrorDto>().code)
        }

    @Test
    fun `a fresh player is served every question once in an order of its own`() =
        runServer("feed-fresh") { client ->
            val first = client.guest()
            val second = client.guest()

            val pool = client.wholePool(first)
            assertEquals(pool.size, pool.ids().toSet().size, "a batch must not repeat a question")
            assertTrue(pool.none { it.answeredBefore }, "nothing is answered yet")
            assertTrue(pool.all { it.categories.isNotEmpty() }, "every question is filed under a category")
            assertTrue(pool.any { it.categories.size > 1 }, "and a question under several is sent with all of them")

            val other = client.wholePool(second)
            assertEquals(pool.ids().toSet(), other.ids().toSet())
            // The order is random per request, so this can fail by pure chance: two random orders
            // of n questions agree with probability 1/n!, which for the 24 seeds is about 1.6e-24.
            assertTrue(pool.size >= 12, "too few seeds for the odds above to stay negligible")
            assertNotEquals(pool.ids(), other.ids(), "two players were served the same order")
        }

    @Test
    fun `once everything is answered the next cycle serves every question again`() =
        runServer("feed-loop") { client ->
            val player = client.guest()
            val pool = client.wholePool(player)
            pool.forEach { question -> client.vote(player, question.id, OptionSide.A) }

            val looped = client.wholePool(player)

            // Never empty while there are questions. That each cycle comes round in a new order, and
            // serves each question once however it is batched, is pinned by QuestionStoreTest.
            assertEquals(pool.ids().toSet(), looped.ids().toSet())
            assertEquals(looped.size, looped.ids().toSet().size, "a looped batch must not repeat a question")
            assertTrue(looped.all { it.answeredBefore }, "every question in the new cycle was answered before")
            assertEquals(1, client.batch(player, "?${WyrApi.Query.LIMIT}=1").size)
        }

    @Test
    fun `a batch holds only what is still due in the cycle`() =
        runServer("feed-due") { client ->
            val player = client.guest()
            val pool = client.wholePool(player)
            val left = pool.take(3)
            pool.drop(3).forEach { question -> client.vote(player, question.id, OptionSide.B) }

            val batch = client.batch(player, "?${WyrApi.Query.LIMIT}=5")

            assertEquals(left.ids().toSet(), batch.ids().toSet(), "no top-up with questions answered this cycle")
            assertEquals(3, batch.size)
            assertTrue(batch.none { it.answeredBefore })
        }

    @Test
    fun `a category with nothing due is served again until the whole cycle is done`() =
        runServer("feed-category") { client ->
            val player = client.guest()
            val food = "&${WyrApi.Query.CATEGORY}=${QuestionCategory.FOOD.name}"
            val pool = client.wholePool(player)
            val foodPool = client.wholePool(player, food)
            assertTrue(foodPool.size >= 2 && foodPool.all { QuestionCategory.FOOD in it.categories })

            val last = foodPool.first()
            foodPool.drop(1).forEach { question -> client.vote(player, question.id, OptionSide.A) }
            assertEquals(listOf(last.id), client.wholePool(player, food).ids(), "only the food still due")

            client.vote(player, last.id, OptionSide.A)
            val again = client.wholePool(player, food)

            assertEquals(foodPool.ids().toSet(), again.ids().toSet(), "all of the food again, rather than nothing")
            assertTrue(again.all { it.answeredBefore })
            // The cycle is the player's, not the category's, so it has not moved on for the rest.
            val unfiltered = client.wholePool(player)
            assertEquals(pool.ids().toSet() - foodPool.ids().toSet(), unfiltered.ids().toSet())
            assertFalse(unfiltered.any { it.answeredBefore })
        }

    @Test
    fun `several categories serve every question filed under any of them once`() =
        runServer("feed-categories") { client ->
            val player = client.guest()
            val category = WyrApi.Query.CATEGORY
            val food = client.wholePool(player, "&$category=${QuestionCategory.FOOD.name}").ids()
            val lifestyle = client.wholePool(player, "&$category=${QuestionCategory.LIFESTYLE.name}").ids()
            assertTrue(food.any { it in lifestyle }, "no question is filed under both")

            val either =
                client.wholePool(
                    player,
                    "&$category=${QuestionCategory.FOOD.name}&$category=${QuestionCategory.LIFESTYLE.name}",
                )

            assertEquals((food + lifestyle).toSet(), either.ids().toSet())
            assertEquals(either.size, either.ids().toSet().size, "each once, though some are filed under both")
        }

    @Test
    fun `a batch holds as many questions as asked for within bounds`() =
        runServer("feed-limit") { client ->
            val player = client.guest()
            val pool = client.wholePool(player)
            assertTrue(pool.size > WyrApi.Limits.DEFAULT_PAGE_SIZE, "too few seeds to tell the default apart")

            assertEquals(WyrApi.Limits.DEFAULT_PAGE_SIZE, client.batch(player).size)
            assertEquals(3, client.batch(player, "?${WyrApi.Query.LIMIT}=3").size)
            assertEquals(1, client.batch(player, "?${WyrApi.Query.LIMIT}=0").size, "a limit below 1 is raised to 1")
            assertEquals(pool.size, client.batch(player, "?${WyrApi.Query.LIMIT}=100000").size)

            val malformed = client.feed(player, "?${WyrApi.Query.LIMIT}=abc")
            assertEquals(HttpStatusCode.BadRequest, malformed.status)
            assertEquals(ErrorCode.VALIDATION_FAILED, malformed.body<ErrorDto>().code)
        }

    @Test
    fun `a skip takes the question out of this cycle, pays nothing, and it comes back in the next`() =
        runServer("skip") { client ->
            val player = client.guest()
            val pool = client.wholePool(player)
            val skipped = pool.first()

            val response = client.skip(player, skipped.id)

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals(pool.ids().toSet() - skipped.id, client.wholePool(player).ids().toSet())
            assertEquals(
                PlayerStatsDto(
                    playerId = player.playerId,
                    totalPoints = 0,
                    answersGiven = 0,
                    questionsAnswered = 0,
                    cycle = 1,
                    dueThisCycle = pool.size - 1,
                ),
                client.stats(player),
                "nothing paid or counted, and one question fewer due",
            )

            pool.drop(1).forEach { question -> client.vote(player, question.id, OptionSide.A) }
            assertEquals(0, client.stats(player).dueThisCycle, "only the skipped one was left, so the cycle is done")

            val next = client.wholePool(player)
            assertEquals(pool.ids().toSet(), next.ids().toSet(), "the next cycle serves it again")
            assertFalse(next.single { it.id == skipped.id }.answeredBefore, "a skip is no answer")
            assertEquals(2, client.stats(player).cycle)
        }

    @Test
    fun `skipping again is harmless and answering after a skip is an ordinary answer`() =
        runServer("skip-repeat") { client ->
            val player = client.guest()
            val question = client.batch(player).first()

            assertEquals(HttpStatusCode.NoContent, client.skip(player, question.id).status)
            assertEquals(HttpStatusCode.NoContent, client.skip(player, question.id).status, "the repeat")
            val answered = client.vote(player, question.id, OptionSide.B)

            assertEquals(Scoring.POINTS_PER_ANSWER, answered.pointsAwarded)
            assertEquals(VoteTallyDto(votesA = 0, votesB = 1), answered.tally, "the vote, and nothing for the skips")
            val stats = client.stats(player)
            assertEquals(Scoring.POINTS_PER_ANSWER, stats.totalPoints)
            assertEquals(1, stats.answersGiven)
            assertEquals(1, stats.questionsAnswered)
        }

    @Test
    fun `skipping a question that does not exist is a not-found`() =
        runServer("skip-missing-question") { client ->
            val session = client.guest()

            listOf("no-such-question", LONGER_THAN_ANY_ID).forEach { questionId ->
                val response = client.skip(session, questionId)

                assertEquals(HttpStatusCode.NotFound, response.status, questionId)
                assertEquals(ErrorCode.QUESTION_NOT_FOUND, response.body<ErrorDto>().code, questionId)
            }
        }

    @Test
    fun `skipping needs a session`() =
        runServer("skip-no-token") { client ->
            val response =
                client.post(WyrApi.Paths.SKIPS) {
                    contentType(ContentType.Application.Json)
                    setBody(SkipRequest("seed-1"))
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ErrorCode.UNAUTHORIZED, response.body<ErrorDto>().code)
        }

    @Test
    fun `a validly signed token for a player that does not exist cannot skip`() =
        runServer("skip-ghost-player") { client ->
            // As for the ghost-player vote, the helper's token for a real player has to pass first.
            val real = client.guest()
            assertEquals(HttpStatusCode.NoContent, client.skip(signAccessToken(real.playerId), "seed-1").status)

            val response = client.skip(signAccessToken("no-such-player"), "seed-1")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ErrorCode.UNAUTHORIZED, response.body<ErrorDto>().code)
        }

    @Test
    fun `a skip body the server cannot use is a validation error`() =
        runServer("malformed-skip") { client ->
            val session = client.guest()

            listOf(
                "malformed json" to "{not json",
                "no questionId" to "{}",
                "blank questionId" to """{"questionId":"  "}""",
                // PostgreSQL refuses a NUL in text, which H2 stores, so only the check can catch it.
                "NUL in questionId" to """{"questionId":"seed-1\u0000"}""",
                "newline in questionId" to """{"questionId":"seed-1\n"}""",
            ).forEach { (case, body) ->
                val response =
                    client.post(WyrApi.Paths.SKIPS) {
                        bearerAuth(session.accessToken)
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status, case)
                assertEquals(ErrorCode.VALIDATION_FAILED, response.body<ErrorDto>().code, case)
            }
        }

    @Test
    fun `a fresh guest's stats are all zero on the first cycle with the whole pool due`() =
        runServer("stats-fresh") { client ->
            val player = client.guest()

            val response = client.me(player)
            val pool = client.wholePool(player)

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                PlayerStatsDto(
                    playerId = player.playerId,
                    totalPoints = 0,
                    answersGiven = 0,
                    questionsAnswered = 0,
                    cycle = 1,
                    dueThisCycle = pool.size,
                ),
                response.body<PlayerStatsDto>(),
            )
            // Sent even where they equal the contract's defaults, which is all a fresh player has but
            // the due count, so the values above are the server's rather than the decoder's.
            assertEquals(
                setOf("playerId", "totalPoints", "answersGiven", "questionsAnswered", "cycle", "dueThisCycle"),
                response.body<JsonObject>().keys,
            )
        }

    @Test
    fun `the stats count every paid answer and agree with the last vote's total`() =
        runServer("stats-answers") { client ->
            val player = client.guest()
            val other = client.guest()
            val pool = client.wholePool(player)
            val (first, second, third) = pool
            client.vote(other, first.id, OptionSide.A)

            client.vote(player, first.id, OptionSide.A)
            client.vote(player, second.id, OptionSide.B)
            client.vote(player, third.id, OptionSide.A)
            client.vote(player, first.id, OptionSide.B, attemptId = "re-answer")
            val replay = client.vote(player, first.id, OptionSide.B, attemptId = "re-answer")
            assertTrue(replay.replayed)

            val stats = client.stats(player)

            assertEquals(player.playerId, stats.playerId)
            assertEquals(replay.totalPoints, stats.totalPoints, "the total the last vote reported")
            assertEquals(4, stats.answersGiven, "the re-answer counts, the replay does not")
            assertEquals(3, stats.questionsAnswered, "distinct questions, and only this player's")
            assertEquals(1, stats.cycle)
            assertEquals(pool.size - 3, stats.dueThisCycle)
        }

    @Test
    fun `the stats show the next cycle only once the feed has started it`() =
        runServer("stats-cycle") { client ->
            val player = client.guest()
            val pool = client.wholePool(player)
            pool.forEach { question -> client.vote(player, question.id, OptionSide.A) }

            // The cycle is finished, but the next one starts only on the next feed request.
            val finished = client.stats(player)
            assertEquals(1, finished.cycle)
            assertEquals(0, finished.dueThisCycle)
            assertEquals(pool.size, finished.questionsAnswered)
            assertEquals(finished, client.stats(player), "reading the stats does not start it")

            client.batch(player, "?${WyrApi.Query.LIMIT}=1")
            val started = client.stats(player)
            assertEquals(2, started.cycle)
            assertEquals(pool.size, started.dueThisCycle)

            client.vote(player, pool.first().id, OptionSide.B)
            val answered = client.stats(player)
            assertEquals(pool.size - 1, answered.dueThisCycle)
            assertEquals(pool.size, answered.questionsAnswered, "answered in both cycles, still one question")
            assertEquals(pool.size + 1, answered.answersGiven)
            assertEquals(pool.size + 1, answered.totalPoints)
        }

    @Test
    fun `the stats need a session`() =
        runServer("stats-no-token") { client ->
            val response = client.get(WyrApi.Paths.ME)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ErrorCode.UNAUTHORIZED, response.body<ErrorDto>().code)
        }

    @Test
    fun `a validly signed token for a player that does not exist gets no stats`() =
        runServer("stats-ghost-player") { client ->
            // As for the ghost-player vote, the helper's token for a real player has to pass first.
            val real = client.guest()
            val accepted = client.get(WyrApi.Paths.ME) { bearerAuth(signAccessToken(real.playerId)) }
            assertEquals(HttpStatusCode.OK, accepted.status)

            val response = client.get(WyrApi.Paths.ME) { bearerAuth(signAccessToken("no-such-player")) }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ErrorCode.UNAUTHORIZED, response.body<ErrorDto>().code)
        }

    @Test
    fun `a submission is stored pending with its options trimmed and pays nothing`() =
        runServer("submit") { client ->
            val author = client.guest()
            val request =
                SubmitQuestionRequest(
                    optionA = "  Be able to fly ",
                    // A line separator is whitespace too, so it is trimmed at an end, not refused.
                    optionB = "\tBreathe underwater\n\u2028",
                    categories = listOf(QuestionCategory.SUPERPOWERS),
                )
            val before = System.currentTimeMillis()

            val response = client.submit(author, request)

            val after = System.currentTimeMillis()
            assertEquals(HttpStatusCode.Created, response.status)
            val submission = response.body<SubmissionDto>()
            assertTrue(submission.id.isNotBlank())
            assertEquals(
                SubmissionDto(
                    id = submission.id,
                    optionA = "Be able to fly",
                    optionB = "Breathe underwater",
                    categories = listOf(QuestionCategory.SUPERPOWERS),
                    status = QuestionStatus.PENDING,
                    rejectionReason = null,
                    submittedAt = submission.submittedAt,
                ),
                submission,
            )
            assertTrue(submission.submittedAt in before..after, "stored while the request ran")
            assertEquals(0, client.stats(author).totalPoints, "a submission earns nothing")
        }

    @Test
    fun `a pending submission is served to nobody and nobody can answer or skip it`() =
        runServer("submit-pending") { client ->
            val author = client.guest()
            val player = client.guest()
            val pending = client.submitted(author, SubmitQuestionRequest("Fly", "Swim", listOf(QuestionCategory.FOOD)))

            listOf("the author" to author, "another player" to player).forEach { (who, session) ->
                val pool = client.wholePool(session)
                assertFalse(pool.any { it.id == pending.id }, "$who is not served it")
                assertEquals(pool.size, client.stats(session).dueThisCycle, "nor is it due for $who")

                val vote = client.castVote(session, VoteRequest(pending.id, OptionSide.A, attemptId = "attempt-1"))
                assertEquals(HttpStatusCode.NotFound, vote.status, "$who answering it")
                assertEquals(ErrorCode.QUESTION_NOT_FOUND, vote.body<ErrorDto>().code)
                val skip = client.skip(session, pending.id)
                assertEquals(HttpStatusCode.NotFound, skip.status, "$who skipping it")
                assertEquals(ErrorCode.QUESTION_NOT_FOUND, skip.body<ErrorDto>().code)
            }
        }

    @Test
    fun `a submission that breaks a content rule is refused as the player's to put right`() =
        runServer("submit-invalid") { client ->
            val author = client.guest()
            val tooLong = "x".repeat(WyrApi.Limits.MAX_OPTION_LENGTH + 1)

            listOf(
                "blank optionA" to SubmitQuestionRequest("   ", "Swim", listOf(QuestionCategory.FOOD)),
                "empty optionB" to SubmitQuestionRequest("Fly", "", listOf(QuestionCategory.FOOD)),
                "optionA too long" to SubmitQuestionRequest(tooLong, "Swim", listOf(QuestionCategory.FOOD)),
                "optionB too long" to SubmitQuestionRequest("Fly", tooLong, listOf(QuestionCategory.FOOD)),
                "the same options ignoring case" to SubmitQuestionRequest("Fly", "fLY", listOf(QuestionCategory.FOOD)),
                "the same options once trimmed" to
                    SubmitQuestionRequest("  Fly ", "fly\n", listOf(QuestionCategory.FOOD)),
                // PostgreSQL refuses a NUL in text, which H2 stores, so only the check can catch it. Neither
                // a NUL nor a DEL is whitespace, so trimming leaves one at either end for the check.
                "trailing NUL in optionA" to SubmitQuestionRequest("Fly\u0000", "Swim", listOf(QuestionCategory.FOOD)),
                "leading DEL in optionB" to SubmitQuestionRequest("Fly", "\u007FSwim", listOf(QuestionCategory.FOOD)),
                "newline inside optionB" to SubmitQuestionRequest("Fly", "Swim\nfast", listOf(QuestionCategory.FOOD)),
                "tab inside optionA" to SubmitQuestionRequest("Fly\thigh", "Swim", listOf(QuestionCategory.FOOD)),
                // Line breaks that are not control characters: text layout still breaks the line at each.
                "line separator inside optionA" to
                    SubmitQuestionRequest("Fly\u2028high", "Swim", listOf(QuestionCategory.FOOD)),
                "paragraph separator inside optionB" to
                    SubmitQuestionRequest("Fly", "Swim\u2029fast", listOf(QuestionCategory.FOOD)),
            ).forEach { (case, request) ->
                val response = client.submit(author, request)
                assertEquals(HttpStatusCode.UnprocessableEntity, response.status, case)
                assertEquals(ErrorCode.INVALID_SUBMISSION, response.body<ErrorDto>().code, case)
            }
            assertEquals(emptyList(), client.mySubmissions(author), "none of them was stored")
        }

    @Test
    fun `an option at the length limit is accepted and padding does not count towards it`() =
        runServer("submit-longest") { client ->
            val author = client.guest()
            val longest = "x".repeat(WyrApi.Limits.MAX_OPTION_LENGTH)
            val padded = "  ${"y".repeat(longest.length)}  "

            listOf(
                "at the limit" to SubmitQuestionRequest(longest, "Swim", listOf(QuestionCategory.FOOD)),
                "at the limit once trimmed" to SubmitQuestionRequest("Fly", padded, listOf(QuestionCategory.FOOD)),
            ).forEach { (case, request) ->
                val response = client.submit(author, request)
                assertEquals(HttpStatusCode.Created, response.status, case)
                val stored = response.body<SubmissionDto>()
                assertEquals(longest.length, maxOf(stored.optionA.length, stored.optionB.length), case)
            }
        }

    @Test
    fun `a submission that is malformed or names no real category is a validation error`() =
        runServer("submit-malformed") { client ->
            val author = client.guest()

            listOf(
                "no category" to """{"optionA":"Fly","optionB":"Swim"}""",
                "an empty list of categories" to """{"optionA":"Fly","optionB":"Swim","categories":[]}""",
                "the single category of old" to """{"optionA":"Fly","optionB":"Swim","category":"FOOD"}""",
                "categories not a list" to """{"optionA":"Fly","optionB":"Swim","categories":"FOOD"}""",
                "UNKNOWN category" to """{"optionA":"Fly","optionB":"Swim","categories":["UNKNOWN"]}""",
                "UNKNOWN beside a real category" to
                    """{"optionA":"Fly","optionB":"Swim","categories":["FOOD","UNKNOWN"]}""",
                "unrecognised category" to """{"optionA":"Fly","optionB":"Swim","categories":["FROM_THE_FUTURE"]}""",
                // Decoded as UNKNOWN beside FOOD rather than dropped, so it is not filed under FOOD alone.
                "unrecognised category beside a real one" to
                    """{"optionA":"Fly","optionB":"Swim","categories":["FOOD","FROM_THE_FUTURE"]}""",
                "no optionB" to """{"optionA":"Fly","categories":["FOOD"]}""",
                "malformed json" to "{not json",
                // Malformed first: the content is not judged in a request no correct client sends.
                "UNKNOWN category and a blank option" to
                    """{"optionA":" ","optionB":"Swim","categories":["UNKNOWN"]}""",
                "no category and a blank option" to """{"optionA":" ","optionB":"Swim","categories":[]}""",
            ).forEach { (case, body) ->
                val response =
                    client.post(WyrApi.Paths.QUESTIONS) {
                        bearerAuth(author.accessToken)
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status, case)
                assertEquals(ErrorCode.VALIDATION_FAILED, response.body<ErrorDto>().code, case)
            }
            assertEquals(emptyList(), client.mySubmissions(author), "none of them was stored")
        }

    @Test
    fun `a submission is filed under every category it names once each and in declaration order`() =
        runServer("submit-categories") { client ->
            val author = client.guest()
            val named = listOf(QuestionCategory.SUPERPOWERS, QuestionCategory.FOOD, QuestionCategory.SUPERPOWERS)

            val submission = client.submitted(author, SubmitQuestionRequest("Fly", "Swim", named))

            assertEquals(listOf(QuestionCategory.FOOD, QuestionCategory.SUPERPOWERS), submission.categories)
            assertEquals(listOf(submission), client.mySubmissions(author), "and listed as it was answered")
        }

    @Test
    fun `a player may have only so many submissions pending at once`() =
        runServer("submit-limit") { client ->
            val author = client.guest()
            repeat(WyrApi.Limits.MAX_PENDING_SUBMISSIONS) { index ->
                val request = SubmitQuestionRequest("Option $index", "Other $index", listOf(QuestionCategory.RANDOM))
                assertEquals(HttpStatusCode.Created, client.submit(author, request).status, "submission ${index + 1}")
            }

            val refused =
                client.submit(
                    author,
                    SubmitQuestionRequest("One", "Too many", listOf(QuestionCategory.RANDOM)),
                )

            assertEquals(HttpStatusCode.Conflict, refused.status)
            assertEquals(ErrorCode.SUBMISSION_LIMIT, refused.body<ErrorDto>().code)
            assertEquals(WyrApi.Limits.MAX_PENDING_SUBMISSIONS, client.mySubmissions(author).size)
            assertEquals(
                HttpStatusCode.Created,
                client
                    .submit(
                        client.guest(),
                        SubmitQuestionRequest("One", "Too many", listOf(QuestionCategory.RANDOM)),
                    ).status,
                "the limit is per author",
            )
        }

    @Test
    fun `an author's submissions are listed newest first and nobody else's are`() =
        runServer("my-questions") { client ->
            val author = client.guest()
            val other = client.guest()
            val submitted =
                List(3) { index ->
                    client.submitted(
                        author,
                        SubmitQuestionRequest("A $index", "B $index", listOf(QuestionCategory.FOOD)),
                    )
                }
            val theirs =
                client.submitted(
                    other,
                    SubmitQuestionRequest("Theirs", "Not ours", listOf(QuestionCategory.ETHICS)),
                )

            val listed = client.mySubmissions(author)

            assertEquals(submitted.toSet(), listed.toSet(), "as each submission was answered, and only the author's")
            assertEquals(listed.sortedByDescending { it.submittedAt }, listed, "newest first")
            assertEquals(listOf(theirs), client.mySubmissions(other))
            assertEquals(emptyList(), client.mySubmissions(client.guest()), "a player who submitted nothing")
        }

    @Test
    fun `listing one's submissions needs a session`() =
        runServer("my-questions-no-token") { client ->
            val response = client.get(WyrApi.Paths.MY_QUESTIONS)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ErrorCode.UNAUTHORIZED, response.body<ErrorDto>().code)
        }

    @Test
    fun `a validly signed token for a player that does not exist lists no submissions`() =
        runServer("my-questions-ghost-player") { client ->
            // As for the ghost-player vote, the helper's token for a real player has to pass first.
            val real = client.guest()
            val accepted = client.get(WyrApi.Paths.MY_QUESTIONS) { bearerAuth(signAccessToken(real.playerId)) }
            assertEquals(HttpStatusCode.OK, accepted.status)

            val response = client.get(WyrApi.Paths.MY_QUESTIONS) { bearerAuth(signAccessToken("no-such-player")) }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ErrorCode.UNAUTHORIZED, response.body<ErrorDto>().code)
        }

    @Test
    fun `submitting needs a session`() =
        runServer("submit-no-token") { client ->
            val response =
                client.post(WyrApi.Paths.QUESTIONS) {
                    contentType(ContentType.Application.Json)
                    setBody(SubmitQuestionRequest("Fly", "Swim", listOf(QuestionCategory.FOOD)))
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ErrorCode.UNAUTHORIZED, response.body<ErrorDto>().code)
        }

    @Test
    fun `a validly signed token for a player that does not exist cannot submit`() =
        runServer("submit-ghost-player") { client ->
            // As for the ghost-player vote, the helper's token for a real player has to pass first.
            val real = client.guest()
            val request = SubmitQuestionRequest("Fly", "Swim", listOf(QuestionCategory.FOOD))
            assertEquals(HttpStatusCode.Created, client.submit(signAccessToken(real.playerId), request).status)

            val response = client.submit(signAccessToken("no-such-player"), request)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ErrorCode.UNAUTHORIZED, response.body<ErrorDto>().code)
        }

    @Test
    fun `the moderator's queue lists every player's pending submissions oldest first and names no author`() =
        runServer("admin-queue") { client ->
            val (author, other) = client.guest() to client.guest()
            val submitted =
                listOf(author, other, author).mapIndexed { index, session ->
                    client.submitted(
                        session,
                        SubmitQuestionRequest("A $index", "B $index", listOf(QuestionCategory.FOOD)),
                    )
                }

            val response = client.moderatorQueue()

            val queue = response.body<SubmissionListDto>().submissions
            assertEquals(submitted.sortedWith(compareBy({ it.submittedAt }, { it.id })), queue, "as each was answered")
            assertEquals(queue, client.queue("?${WyrApi.Query.STATUS}=PENDING"), "which is the default")
            assertEquals(queue.take(2), client.queue("?${WyrApi.Query.LIMIT}=2"), "the head of the queue")
            assertEquals(
                setOf("id", "optionA", "optionB", "categories", "status", "rejectionReason", "submittedAt"),
                response
                    .body<JsonObject>()
                    .getValue("submissions")
                    .jsonArray
                    .first()
                    .jsonObject.keys,
                "as its author sees it, and nothing about who that is",
            )
            listOf("APPROVED", "REJECTED").forEach { status ->
                assertEquals(emptyList(), client.queue("?${WyrApi.Query.STATUS}=$status"), "no seed is a submission")
            }
        }

    @Test
    fun `the moderator's queue refuses a status that is not a real one or more than one`() =
        runServer("admin-queue-malformed") { client ->
            val status = WyrApi.Query.STATUS

            listOf(
                "UNKNOWN" to "?$status=UNKNOWN",
                "unrecognised" to "?$status=FROM_THE_FUTURE",
                "lower case" to "?$status=pending",
                "empty" to "?$status=",
                "two" to "?$status=PENDING&$status=REJECTED",
                "comma-separated" to "?$status=PENDING,REJECTED",
                "a limit that is not a number" to "?${WyrApi.Query.LIMIT}=many",
            ).forEach { (case, query) ->
                val response = client.moderatorQueue(query)
                assertEquals(HttpStatusCode.BadRequest, response.status, case)
                assertEquals(ErrorCode.VALIDATION_FAILED, response.body<ErrorDto>().code, case)
            }
        }

    @Test
    fun `an admin route without the admin token is forbidden and never unauthorized`() =
        runServer("admin-forbidden") { client ->
            val player = client.guest()

            listOf<Pair<String, HttpRequestBuilder.() -> Unit>>(
                "no token" to {},
                "an empty token" to { header(WyrApi.Headers.ADMIN_TOKEN, "") },
                "another token" to { header(WyrApi.Headers.ADMIN_TOKEN, "another-token-0123456789abcdef") },
                "the token in another case" to { header(WyrApi.Headers.ADMIN_TOKEN, TEST_ADMIN_TOKEN.uppercase()) },
                "the token cut short" to { header(WyrApi.Headers.ADMIN_TOKEN, TEST_ADMIN_TOKEN.dropLast(1)) },
                "the token and more" to { header(WyrApi.Headers.ADMIN_TOKEN, TEST_ADMIN_TOKEN + "0") },
                // Authorization carries the player's bearer token, never the moderator's credential.
                "the token as a bearer token" to { bearerAuth(TEST_ADMIN_TOKEN) },
                "a player's live session" to { bearerAuth(player.accessToken) },
                // A 401 would have the client refresh this session, and then replace it.
                "a session that does not verify" to { bearerAuth("not-a-token") },
            ).forEach { (case, credentials) ->
                client.everyAdminRoute(credentials).forEach { (route, response) ->
                    assertEquals(HttpStatusCode.Forbidden, response.status, "$case on $route")
                    assertEquals(ErrorCode.FORBIDDEN, response.body<ErrorDto>().code, "$case on $route")
                }
            }
        }

    @Test
    fun `the admin token is taken whatever player session travels beside it`() =
        runServer("admin-beside-session") { client ->
            val player = client.guest()

            // The client's bearer provider sends the player's token with every request, a stale one too.
            listOf<Pair<String, HttpRequestBuilder.() -> Unit>>(
                "no session" to {},
                "a player's live session" to { bearerAuth(player.accessToken) },
                "a session that does not verify" to { bearerAuth("not-a-token") },
                "a session whose player does not exist" to { bearerAuth(signAccessToken("no-such-player")) },
            ).forEach { (case, session) ->
                val response =
                    client.get(WyrApi.Paths.ADMIN_SUBMISSIONS) {
                        session()
                        header(WyrApi.Headers.ADMIN_TOKEN, TEST_ADMIN_TOKEN)
                    }
                assertEquals(HttpStatusCode.OK, response.status, case)
            }
        }

    @Test
    fun `with no admin token configured the admin routes are not there at all`() =
        runServer("admin-off", adminToken = null) { client ->
            val absent = client.get("/${WyrApi.VERSION}/no-such-route")
            assertEquals(HttpStatusCode.NotFound, absent.status)

            listOf<Pair<String, HttpRequestBuilder.() -> Unit>>(
                "no token" to {},
                "a token" to { header(WyrApi.Headers.ADMIN_TOKEN, TEST_ADMIN_TOKEN) },
            ).forEach { (case, credentials) ->
                client.everyAdminRoute(credentials).forEach { (route, response) ->
                    assertEquals(HttpStatusCode.NotFound, response.status, "$case on $route")
                    assertEquals(absent.bodyAsText(), response.bodyAsText(), "$case on $route, as a path never served")
                }
            }
        }

    @Test
    fun `an approved submission is due at once for every player in their current cycle its author included`() =
        runServer("admin-approve") { client ->
            val (author, midway, finished) = Triple(client.guest(), client.guest(), client.guest())
            val pending = client.submitted(author, SubmitQuestionRequest("Fly", "Swim", listOf(QuestionCategory.FOOD)))
            val seeds = client.wholePool(finished)
            seeds.forEach { seed -> client.vote(finished, seed.id, OptionSide.A) }
            client.vote(midway, seeds.first().id, OptionSide.A)
            assertEquals(0, client.stats(finished).dueThisCycle, "the cycle is finished")

            val response = client.approve(pending.id)

            assertEquals(HttpStatusCode.OK, response.status)
            val approved = response.body<SubmissionDto>()
            assertEquals(pending.copy(status = QuestionStatus.APPROVED), approved)
            assertEquals(listOf(approved), client.mySubmissions(author), "and its author sees it so")
            assertEquals(seeds.size + 1, client.stats(author).dueThisCycle, "due for its author")
            assertEquals(seeds.size, client.stats(midway).dueThisCycle, "for a player midway through the cycle")
            assertEquals(1, client.stats(finished).dueThisCycle, "and for one with nothing else left in it")
            assertEquals(listOf(pending.id), client.batch(finished).ids(), "served in that cycle")
            assertEquals(1, client.stats(finished).cycle, "rather than starting the next one")
            assertTrue(pending.id in client.wholePool(author).ids(), "served to its author like anyone")
            assertEquals(
                Scoring.POINTS_PER_ANSWER,
                client.vote(author, pending.id, OptionSide.B).pointsAwarded,
                "and answered by them like any other",
            )
            assertEquals(emptyList(), client.queue(), "out of the queue")
            assertEquals(listOf(approved), client.queue("?${WyrApi.Query.STATUS}=APPROVED"))
        }

    @Test
    fun `a rejected submission is served to nobody and its author sees why`() =
        runServer("admin-reject") { client ->
            val (author, player) = client.guest() to client.guest()
            val pending = client.submitted(author, SubmitQuestionRequest("Fly", "Swim", listOf(QuestionCategory.FOOD)))

            val response = client.reject(pending.id, "  Not really a dilemma ")

            assertEquals(HttpStatusCode.OK, response.status)
            val rejected = response.body<SubmissionDto>()
            assertEquals(
                pending.copy(status = QuestionStatus.REJECTED, rejectionReason = "Not really a dilemma"),
                rejected,
                "its reason stored trimmed",
            )
            assertEquals(listOf(rejected), client.mySubmissions(author))
            listOf("the author" to author, "another player" to player).forEach { (who, session) ->
                val pool = client.wholePool(session)
                assertFalse(pending.id in pool.ids(), "$who is not served it")
                assertEquals(pool.size, client.stats(session).dueThisCycle, "nor is it due for $who")
                val vote = client.castVote(session, VoteRequest(pending.id, OptionSide.A, attemptId = "attempt-1"))
                assertEquals(ErrorCode.QUESTION_NOT_FOUND, vote.body<ErrorDto>().code, "$who answering it")
                assertEquals(ErrorCode.QUESTION_NOT_FOUND, client.skip(session, pending.id).body<ErrorDto>().code)
            }
            assertEquals(emptyList(), client.queue(), "out of the queue")
            assertEquals(listOf(rejected), client.queue("?${WyrApi.Query.STATUS}=REJECTED"))
        }

    @Test
    fun `an approval naming categories changes what a category filter finds`() =
        runServer("admin-recategorize") { client ->
            val (author, player) = client.guest() to client.guest()
            val pending = client.submitted(author, SubmitQuestionRequest("Fly", "Swim", listOf(QuestionCategory.FOOD)))
            val named = listOf(QuestionCategory.SUPERPOWERS, QuestionCategory.ETHICS, QuestionCategory.SUPERPOWERS)

            val approved = client.approve(pending.id, named).body<SubmissionDto>()

            val chosen = listOf(QuestionCategory.ETHICS, QuestionCategory.SUPERPOWERS)
            assertEquals(chosen, approved.categories, "each once, in declaration order")
            assertEquals(listOf(approved), client.mySubmissions(author), "and its author sees them")
            assertEquals(chosen, client.wholePool(player).single { it.id == pending.id }.categories)
            assertFalse(pending.id in client.wholePool(player, "&category=FOOD").ids(), "no longer filed under food")
            listOf("ETHICS", "SUPERPOWERS").forEach { category ->
                assertTrue(pending.id in client.wholePool(player, "&category=$category").ids(), category)
            }

            // Naming none keeps the author's.
            val kept = client.submitted(author, SubmitQuestionRequest("Run", "Walk", listOf(QuestionCategory.RANDOM)))
            assertEquals(listOf(QuestionCategory.RANDOM), client.approve(kept.id).body<SubmissionDto>().categories)
        }

    @Test
    fun `a decision on a question that is not pending is a conflict and changes nothing`() =
        runServer("admin-conflict") { client ->
            val author = client.guest()
            val approved = client.approve(client.submitted(author, question("Approved")).id).body<SubmissionDto>()
            val rejected = client.reject(client.submitted(author, question("Rejected")).id, "No").body<SubmissionDto>()

            listOf("approved" to approved.id, "rejected" to rejected.id, "a seed" to "seed-1").forEach { (case, id) ->
                listOf(
                    "approving" to client.approve(id, listOf(QuestionCategory.ETHICS)),
                    "rejecting" to client.reject(id, "Changed my mind"),
                ).forEach { (decision, response) ->
                    assertEquals(HttpStatusCode.Conflict, response.status, "$decision $case")
                    assertEquals(ErrorCode.ALREADY_DECIDED, response.body<ErrorDto>().code, "$decision $case")
                }
            }

            assertEquals(setOf(approved, rejected), client.mySubmissions(author).toSet(), "as first decided")
            val seed = client.wholePool(author).single { it.id == "seed-1" }
            assertFalse(QuestionCategory.ETHICS in seed.categories, "a seed's categories kept too")
        }

    @Test
    fun `deciding a question that does not exist is a not-found`() =
        runServer("admin-missing-question") { client ->
            listOf(
                "an unknown id" to "no-such-question",
                // Nothing on the wire bounds an id, so this one reaches the update and is simply not found.
                "an id longer than any" to LONGER_THAN_ANY_ID,
            ).forEach { (case, id) ->
                val decisions = listOf("approving" to client.approve(id), "rejecting" to client.reject(id, "No"))
                decisions.forEach { (how, response) ->
                    assertEquals(HttpStatusCode.NotFound, response.status, "$how $case")
                    assertEquals(ErrorCode.QUESTION_NOT_FOUND, response.body<ErrorDto>().code, "$how $case")
                }
            }
        }

    @Test
    fun `a decision the server cannot use is a validation error and decides nothing`() =
        runServer("admin-malformed") { client ->
            val pending = client.submitted(client.guest(), question("Pending"))
            val id = pending.id
            val tooLong = "x".repeat(WyrApi.Limits.MAX_REJECTION_REASON_LENGTH + 1)
            val approvals = WyrApi.Paths.ADMIN_APPROVALS
            val rejections = WyrApi.Paths.ADMIN_REJECTIONS

            listOf(
                "an approval with no questionId" to (approvals to """{"categories":["FOOD"]}"""),
                "an approval of a blank id" to (approvals to """{"questionId":"  "}"""),
                "an approval of an id with a NUL" to (approvals to """{"questionId":"$id\u0000"}"""),
                "an approval naming UNKNOWN" to (approvals to """{"questionId":"$id","categories":["UNKNOWN"]}"""),
                // Decoded as UNKNOWN beside FOOD rather than dropped, so it is not filed under FOOD alone.
                "an approval naming an unrecognised category beside a real one" to
                    (approvals to """{"questionId":"$id","categories":["FOOD","FROM_THE_FUTURE"]}"""),
                "an approval whose categories are not a list" to
                    (approvals to """{"questionId":"$id","categories":"FOOD"}"""),
                "an approval that is not json" to (approvals to "{not json"),
                "a rejection with no reason" to (rejections to """{"questionId":"$id"}"""),
                "a rejection with no questionId" to (rejections to """{"reason":"No"}"""),
                "a rejection with an empty reason" to (rejections to """{"questionId":"$id","reason":""}"""),
                "a rejection with a blank reason" to (rejections to """{"questionId":"$id","reason":" \t "}"""),
                "a rejection with a reason too long" to (rejections to """{"questionId":"$id","reason":"$tooLong"}"""),
                "a rejection with a newline inside its reason" to
                    (rejections to """{"questionId":"$id","reason":"Not\nreally"}"""),
                // PostgreSQL refuses a NUL in text, which H2 stores, so only the check can catch it.
                "a rejection with a NUL in its reason" to
                    (rejections to """{"questionId":"$id","reason":"Not\u0000"}"""),
                "a rejection with a line separator inside its reason" to
                    (rejections to """{"questionId":"$id","reason":"Not\u2028really"}"""),
            ).forEach { (case, request) ->
                val (path, body) = request
                val response = client.moderate(path, body)
                assertEquals(HttpStatusCode.BadRequest, response.status, case)
                assertEquals(ErrorCode.VALIDATION_FAILED, response.body<ErrorDto>().code, case)
            }
            assertEquals(listOf(pending), client.queue(), "still waiting, as submitted")
        }

    @Test
    fun `a reason at the length limit is accepted and padding does not count towards it`() =
        runServer("admin-longest-reason") { client ->
            val longest = "x".repeat(WyrApi.Limits.MAX_REJECTION_REASON_LENGTH)
            val pending = client.submitted(client.guest(), question("Pending"))

            val response = client.reject(pending.id, "  $longest\n")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(longest, response.body<SubmissionDto>().rejectionReason)
        }

    @Test
    fun `without the admin token a decision is refused before its body is read`() =
        runServer("admin-forbidden-body") { client ->
            val pending = client.submitted(client.guest(), question("Pending"))

            // Nothing about the request answers a caller without the token: not whether it parses, nor
            // whether the question exists.
            listOf(
                "malformed" to (WyrApi.Paths.ADMIN_APPROVALS to "{not json"),
                "a real approval" to (WyrApi.Paths.ADMIN_APPROVALS to """{"questionId":"${pending.id}"}"""),
                "a real rejection" to
                    (WyrApi.Paths.ADMIN_REJECTIONS to """{"questionId":"${pending.id}","reason":"No"}"""),
            ).forEach { (case, request) ->
                val (path, body) = request
                val response =
                    client.post(path) {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status, case)
            }
            assertEquals(listOf(pending), client.queue(), "and nothing was decided")
        }

    /** A server on its own database, moderated with [adminToken], or with moderation off for none. */
    private fun runServer(
        databaseName: String,
        adminToken: String? = TEST_ADMIN_TOKEN,
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) = testApplication {
        val database = testDatabaseFor(databaseName)

        val config =
            ServerConfig(
                port = 0,
                jdbcUrl = database.jdbcUrl,
                dbUser = database.user,
                dbPassword = database.password,
                jwtSecret = "test-secret",
                jwtIssuer = "wyr-test",
                jwtAudience = "wyr-test-client",
                accessTokenTtlSeconds = 300,
                refreshTokenTtlSeconds = 3_600,
                allowedWebOrigins = emptyList(),
                adminToken = adminToken,
            )

        application { wyrModule(config) }

        val client =
            createClient {
                // Left at the default (false) so a test can assert on a failure status directly.
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }

        block(client)
    }

    private suspend fun HttpClient.guest(): SessionDto = post(WyrApi.Paths.AUTH_GUEST).body()

    private suspend fun HttpClient.feed(
        session: SessionDto,
        query: String = "",
    ): HttpResponse = get(WyrApi.Paths.QUESTIONS + query) { bearerAuth(session.accessToken) }

    private suspend fun HttpClient.batch(
        session: SessionDto,
        query: String = "",
    ): List<QuestionDto> = feed(session, query).body<QuestionPageDto>().questions

    /**
     * One batch at the maximum size: every question in the pool, or in the category [extraQuery]
     * names, for as long as the pool is smaller than that, as the seeds are.
     */
    private suspend fun HttpClient.wholePool(
        session: SessionDto,
        extraQuery: String = "",
    ): List<QuestionDto> {
        val batch = batch(session, "?${WyrApi.Query.LIMIT}=${WyrApi.Limits.MAX_PAGE_SIZE}$extraQuery")
        assertTrue(batch.size < WyrApi.Limits.MAX_PAGE_SIZE, "the pool no longer fits in one batch")
        return batch
    }

    private fun List<QuestionDto>.ids(): List<String> = map { it.id }

    private suspend fun HttpClient.skip(
        session: SessionDto,
        questionId: String,
    ): HttpResponse = skip(session.accessToken, questionId)

    private suspend fun HttpClient.skip(
        accessToken: String,
        questionId: String,
    ): HttpResponse =
        post(WyrApi.Paths.SKIPS) {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody(SkipRequest(questionId))
        }

    private suspend fun HttpClient.submit(
        session: SessionDto,
        request: SubmitQuestionRequest,
    ): HttpResponse = submit(session.accessToken, request)

    private suspend fun HttpClient.mySubmissions(session: SessionDto): List<SubmissionDto> =
        get(WyrApi.Paths.MY_QUESTIONS) { bearerAuth(session.accessToken) }.body<SubmissionListDto>().submissions

    private suspend fun HttpClient.submitted(
        session: SessionDto,
        request: SubmitQuestionRequest,
    ): SubmissionDto = submit(session, request).body()

    private suspend fun HttpClient.submit(
        accessToken: String,
        request: SubmitQuestionRequest,
    ): HttpResponse =
        post(WyrApi.Paths.QUESTIONS) {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    /** The moderator's queue, asked for with the test server's admin token. */
    private suspend fun HttpClient.moderatorQueue(query: String = ""): HttpResponse =
        get(WyrApi.Paths.ADMIN_SUBMISSIONS + query) { header(WyrApi.Headers.ADMIN_TOKEN, TEST_ADMIN_TOKEN) }

    private suspend fun HttpClient.queue(query: String = ""): List<SubmissionDto> =
        moderatorQueue(query).body<SubmissionListDto>().submissions

    /**
     * One request to each admin route, carrying no credential but what [credentials] adds. A decision
     * names a question that does not exist, so none of them can decide anything.
     */
    private suspend fun HttpClient.everyAdminRoute(
        credentials: HttpRequestBuilder.() -> Unit,
    ): List<Pair<String, HttpResponse>> =
        listOf(
            "the queue" to get(WyrApi.Paths.ADMIN_SUBMISSIONS) { credentials() },
            "an approval" to
                post(WyrApi.Paths.ADMIN_APPROVALS) {
                    credentials()
                    contentType(ContentType.Application.Json)
                    setBody(ApproveSubmissionRequest("no-such-question"))
                },
            "a rejection" to
                post(WyrApi.Paths.ADMIN_REJECTIONS) {
                    credentials()
                    contentType(ContentType.Application.Json)
                    setBody(RejectSubmissionRequest("no-such-question", reason = "No"))
                },
        )

    /** Approves [questionId] with the test server's admin token, filing it under [categories] if any. */
    private suspend fun HttpClient.approve(
        questionId: String,
        categories: List<QuestionCategory> = emptyList(),
    ): HttpResponse =
        post(WyrApi.Paths.ADMIN_APPROVALS) {
            header(WyrApi.Headers.ADMIN_TOKEN, TEST_ADMIN_TOKEN)
            contentType(ContentType.Application.Json)
            setBody(ApproveSubmissionRequest(questionId, categories))
        }

    private suspend fun HttpClient.reject(
        questionId: String,
        reason: String,
    ): HttpResponse =
        post(WyrApi.Paths.ADMIN_REJECTIONS) {
            header(WyrApi.Headers.ADMIN_TOKEN, TEST_ADMIN_TOKEN)
            contentType(ContentType.Application.Json)
            setBody(RejectSubmissionRequest(questionId, reason))
        }

    /** Sends [body] as it is to the admin route [path], with the test server's admin token. */
    private suspend fun HttpClient.moderate(
        path: String,
        body: String,
    ): HttpResponse =
        post(path) {
            header(WyrApi.Headers.ADMIN_TOKEN, TEST_ADMIN_TOKEN)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /** A food question to submit, set against its own negation. */
    private fun question(text: String) = SubmitQuestionRequest(text, "Not $text", listOf(QuestionCategory.FOOD))

    private suspend fun HttpClient.me(session: SessionDto): HttpResponse =
        get(WyrApi.Paths.ME) { bearerAuth(session.accessToken) }

    private suspend fun HttpClient.stats(session: SessionDto): PlayerStatsDto = me(session).body()

    /** A fresh answer unless [attemptId] repeats an earlier one. */
    private suspend fun HttpClient.vote(
        session: SessionDto,
        questionId: String,
        choice: OptionSide,
        attemptId: String = UUID.randomUUID().toString(),
    ): VoteResultDto = castVote(session, VoteRequest(questionId, choice, attemptId)).body()

    private suspend fun HttpClient.castVote(
        session: SessionDto,
        request: VoteRequest,
    ): HttpResponse =
        post(WyrApi.Paths.VOTES) {
            bearerAuth(session.accessToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    /**
     * Signs an access token the way the server under [runServer] does, for a player id the server
     * never issued one to. The issuer, audience, and default [secret] must match [runServer]'s
     * config; the ghost-player test checks that they still do.
     */
    private fun signAccessToken(
        playerId: String,
        secret: String = "test-secret",
    ): String {
        val env =
            mapOf(
                "JWT_SECRET" to secret,
                "JWT_ISSUER" to "wyr-test",
                "JWT_AUDIENCE" to "wyr-test-client",
            )
        return TokenService(ServerConfig.fromEnvironment(env::get)).issueAccessToken(playerId)
    }

    private companion object {
        /** The admin token [runServer]'s server is moderated with, unless a test turns moderation off. */
        const val TEST_ADMIN_TOKEN = "test-admin-token-0123456789abcdef"

        /**
         * Longer than the 36 characters of every id column, so no question has it. Nothing on the
         * wire bounds a questionId, so it reaches the lookup and is simply not found.
         */
        const val LONGER_THAN_ANY_ID = "seed-1-and-then-far-more-characters-than-any-question-id-can-hold"
    }
}
