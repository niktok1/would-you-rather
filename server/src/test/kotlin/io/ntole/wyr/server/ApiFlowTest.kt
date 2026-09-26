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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.RefreshRequest
import io.ntole.wyr.core.auth.RegisterRequest
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.author.BlockAuthorRequest
import io.ntole.wyr.core.author.UnblockAuthorRequest
import io.ntole.wyr.core.category.CreateCategoryRequest
import io.ntole.wyr.core.category.RenameCategoryRequest
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.error.ErrorDto
import io.ntole.wyr.core.player.PlayerStatsDto
import io.ntole.wyr.core.question.AdminQuestionDto
import io.ntole.wyr.core.question.AdminQuestionPageDto
import io.ntole.wyr.core.question.ApproveSubmissionRequest
import io.ntole.wyr.core.question.QuestionDto
import io.ntole.wyr.core.question.QuestionPageDto
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.core.question.RejectSubmissionRequest
import io.ntole.wyr.core.question.RestoreQuestionRequest
import io.ntole.wyr.core.question.RetireQuestionRequest
import io.ntole.wyr.core.question.SkipRequest
import io.ntole.wyr.core.question.SubmissionDto
import io.ntole.wyr.core.question.SubmissionListDto
import io.ntole.wyr.core.question.SubmitQuestionRequest
import io.ntole.wyr.core.reaction.Reaction
import io.ntole.wyr.core.reaction.ReactionRequest
import io.ntole.wyr.core.reaction.ReactionResultDto
import io.ntole.wyr.core.report.DismissReportsRequest
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteRequest
import io.ntole.wyr.core.vote.VoteResultDto
import io.ntole.wyr.core.vote.VoteTallyDto
import io.ntole.wyr.server.auth.AccountStore
import io.ntole.wyr.server.auth.TokenService
import io.ntole.wyr.server.config.ServerConfig
import io.ntole.wyr.server.db.Sessions
import io.ntole.wyr.server.db.TEST_SEEDS
import io.ntole.wyr.server.db.inTransaction
import io.ntole.wyr.server.db.serverPool
import io.ntole.wyr.server.db.tallyOf
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.vote.Scoring
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * End-to-end coverage of the vertical slice: zero-click session, the question feed, vote, reveal.
 *
 * Each test gets its own in-memory database, keyed by name, so vote counts from one test cannot
 * bleed into another's tally assertions.
 */
class ApiFlowTest {
    /** The database of the server [runServer] started for this test. */
    private var serverDatabase: TestDatabaseSettings? = null

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
            // The voter's own vote is included in the tally they are shown, beside a seed's made-up votes.
            assertEquals(tallyOf(question.id, votesA = 1, votesB = 0), result.tally)
            // A first answer earns the flat point, and that point is the whole running total.
            assertEquals(Scoring.POINTS_PER_ANSWER, result.pointsAwarded)
            assertEquals(Scoring.POINTS_PER_ANSWER, result.totalPoints)
            assertFalse(result.replayed)
        }

    @Test
    fun `a guest's mint answers its session and nothing else, and no recovery route is left`() =
        runServer("mint-only") { client ->
            val minted = client.post(WyrApi.Paths.AUTH_GUEST)
            val session = Json.decodeFromString<SessionDto>(minted.bodyAsText())

            assertEquals(
                setOf("playerId", "accessToken", "refreshToken", "accessTokenExpiresInSeconds"),
                Json.parseToJsonElement(minted.bodyAsText()).jsonObject.keys,
            )
            // The recovery secret's two routes, gone with it (CLAUDE.md §8a).
            listOf("/v1/auth/recover", "/v1/me/recovery-secret").forEach { path ->
                val answer =
                    client.post(path) {
                        bearerAuth(session.accessToken)
                        contentType(ContentType.Application.Json)
                        setBody("""{"recoverySecret":"any"}""")
                    }
                assertEquals(HttpStatusCode.NotFound, answer.status, path)
            }
        }

    /**
     * The players row's old refresh-token columns and its recovery secret's are unused (CLAUDE.md §8b,
     * *Rollbacks*): with all of them dropped, this build still mints, refreshes, serves, scores and
     * reports, so the later migration that drops them leaves a schema a rollback to it can run on.
     */
    @Test
    fun `a guest plays on with the players row's unused columns dropped`() {
        val database = testDatabaseFor("unused-columns-dropped")
        runServer("unused-columns-dropped", database = database) { client ->
            assertEquals(HttpStatusCode.OK, client.get(WyrApi.Paths.HEALTH).status, "booted, and migrated")
            database.dropUnusedPlayersColumns()

            val guest = client.guest()
            val refreshed = client.refresh(guest.refreshToken)
            assertEquals(HttpStatusCode.OK, refreshed.status)
            val session = refreshed.body<SessionDto>()
            val vote =
                client.post(WyrApi.Paths.VOTES) {
                    bearerAuth(session.accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(VoteRequest(client.batch(session).first().id, OptionSide.A, attemptId = "a1"))
                }
            assertEquals(HttpStatusCode.OK, vote.status)
            assertEquals(Scoring.POINTS_PER_ANSWER, client.stats(session).totalPoints)
        }
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
            assertEquals(tallyOf(opened.id, votesA = 1, votesB = 0), majority.tally)
            assertEquals(tallyOf(contested.id, votesA = 2, votesB = 1), minority.tally)

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

            assertEquals(tallyOf(question.id, votesA = 2, votesB = 0), first.tally)
            assertEquals(OptionSide.B, again.yourChoice, "the pick may change")
            assertEquals(
                tallyOf(question.id, votesA = 1, votesB = 1),
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
            assertEquals(tallyOf(question.id, votesA = 0, votesB = 1), replayOfSwitch.tally)
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
    fun `with the grace window off, refresh rotates the token and the old one stops working`() =
        runServer("refresh-rotation", refreshGraceSeconds = 0) { client ->
            val original = client.guest()

            val refreshed: SessionDto = client.refresh(original.refreshToken).body()

            assertEquals(original.playerId, refreshed.playerId, "refresh must not change identity")
            assertNotEquals(original.refreshToken, refreshed.refreshToken)

            // Replaying the consumed token must fail — that is the point of rotating it.
            val replay = client.refresh(original.refreshToken)
            assertEquals(HttpStatusCode.Unauthorized, replay.status)
            assertEquals(ErrorCode.INVALID_REFRESH_TOKEN, replay.body<ErrorDto>().code)
        }

    /**
     * The case the grace window is for (CLAUDE.md §8a): the server rotated the token, the answer never
     * reached the client, and the client still holds only the token the server rotated out. Without the
     * grace, its next refresh is refused and the client replaces the player with a fresh guest.
     */
    @Test
    fun `a refresh whose answer was lost can be sent again with the same token, keeping the player and their points`() =
        runServer("refresh-lost-answer") { client ->
            val original = client.guest()
            client.vote(original, "seed-1", OptionSide.A)
            val lost = client.refresh(original.refreshToken)
            assertEquals(HttpStatusCode.OK, lost.status, "the server ran the refresh; only its answer is lost")

            val retried = client.refresh(original.refreshToken)

            assertEquals(HttpStatusCode.OK, retried.status)
            val session: SessionDto = retried.body()
            assertEquals(original.playerId, session.playerId, "the same player, not a fresh guest")
            assertEquals(1, client.stats(session).totalPoints, "with the points they had")
            assertEquals(HttpStatusCode.OK, client.refresh(session.refreshToken).status, "and a session that lives on")
        }

    /**
     * The same lost answer with the player back hours later, as one whose app was killed mid-refresh can
     * be: with no time bound on the grace, the token the server rotated out still keeps them. A test
     * cannot wait hours, so it restamps the rotation that long ago, which is all the wait would change
     * for the displaced token short of its own expiry, weeks away here.
     */
    @Test
    fun `a refresh whose answer was lost keeps the player when it is sent again hours later`() {
        val database = testDatabaseFor("refresh-lost-hours")
        runServer("refresh-lost-hours", database = database) { client ->
            val original = client.guest()
            client.vote(original, "seed-1", OptionSide.A)
            assertEquals(HttpStatusCode.OK, client.refresh(original.refreshToken).status, "only its answer is lost")
            database.stampLastRotation(original.playerId, ago = 5.hours)

            val retried = client.refresh(original.refreshToken)

            assertEquals(HttpStatusCode.OK, retried.status)
            val session: SessionDto = retried.body()
            assertEquals(original.playerId, session.playerId, "the same player, not a fresh guest")
            assertEquals(1, client.stats(session).totalPoints, "with the points they had")
        }
    }

    /**
     * `REFRESH_GRACE_SECONDS` still bounds the grace when it is set, as its 10 minutes did by default at
     * first: a lost answer sent again within them keeps the player, and one sent again after is refused.
     */
    @Test
    fun `under a time bound, a refresh whose answer was lost keeps the player only within it`() {
        val database = testDatabaseFor("refresh-lost-bounded")
        runServer("refresh-lost-bounded", refreshGraceSeconds = 600, database = database) { client ->
            val inTime = client.guest()
            val late = client.guest()
            assertEquals(HttpStatusCode.OK, client.refresh(inTime.refreshToken).status, "only its answer is lost")
            assertEquals(HttpStatusCode.OK, client.refresh(late.refreshToken).status, "only its answer is lost")
            database.stampLastRotation(inTime.playerId, ago = 9.minutes)
            database.stampLastRotation(late.playerId, ago = 11.minutes)

            val retriedInTime = client.refresh(inTime.refreshToken)
            val retriedLate = client.refresh(late.refreshToken)

            assertEquals(HttpStatusCode.OK, retriedInTime.status, "within the bound")
            assertEquals(inTime.playerId, retriedInTime.body<SessionDto>().playerId)
            assertEquals(HttpStatusCode.Unauthorized, retriedLate.status, "past the bound")
            assertEquals(ErrorCode.INVALID_REFRESH_TOKEN, retriedLate.body<ErrorDto>().code)
        }
    }

    /**
     * The grace lets a displaced token work once more, not for as long as it waits: spending it
     * displaces it for good. The token it displaced in turn, the one the lost answer carried, is then
     * the previous one, and still works, as when two clients sharing one store refresh at once.
     */
    @Test
    fun `a refresh token works once more after its rotation, and never a third time`() =
        runServer("refresh-grace-once") { client ->
            val original = client.guest()
            val first: SessionDto = client.refresh(original.refreshToken).body()
            assertEquals(HttpStatusCode.OK, client.refresh(original.refreshToken).status, "once more")

            val third = client.refresh(original.refreshToken)

            assertEquals(HttpStatusCode.Unauthorized, third.status)
            assertEquals(ErrorCode.INVALID_REFRESH_TOKEN, third.body<ErrorDto>().code)
            val displaced = client.refresh(first.refreshToken)
            assertEquals(HttpStatusCode.OK, displaced.status, "the first refresh's token, displaced by the second")
            assertEquals(original.playerId, displaced.body<SessionDto>().playerId)
        }

    /**
     * With no time bound, what ends a displaced token is the next rotation, here the first use of the
     * token that displaced it, however soon after.
     */
    @Test
    fun `a displaced refresh token dies at the first use of the token that displaced it`() =
        runServer("refresh-displaced-for-good") { client ->
            val original = client.guest()
            val first: SessionDto = client.refresh(original.refreshToken).body()
            assertEquals(HttpStatusCode.OK, client.refresh(first.refreshToken).status, "the new token's first use")

            val late = client.refresh(original.refreshToken)

            assertEquals(HttpStatusCode.Unauthorized, late.status)
            assertEquals(ErrorCode.INVALID_REFRESH_TOKEN, late.body<ErrorDto>().code)
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
    fun `an Authorization header that cannot be parsed is unauthorized, not an internal error`() =
        runServer("malformed-authorization") { client ->
            listOf("Bearer a b", "Bearer", "Bearer =").forEach { header ->
                val response =
                    client.get(WyrApi.Paths.QUESTIONS) {
                        headers.append(HttpHeaders.Authorization, header)
                    }

                assertEquals(HttpStatusCode.Unauthorized, response.status, "\"$header\"")
                assertEquals(ErrorCode.UNAUTHORIZED, response.body<ErrorDto>().code, "\"$header\"")
            }
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
                // RANDOM went away with V6: an installed build that still asks for it gets this.
                "RANDOM" to "?$category=RANDOM",
                "RANDOM beside a real one" to "?$category=FOOD&$category=RANDOM",
                "UNKNOWN" to "?$category=UNKNOWN",
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
            val food = "&${WyrApi.Query.CATEGORY}=${"FOOD"}"
            val pool = client.wholePool(player)
            val foodPool = client.wholePool(player, food)
            assertTrue(foodPool.size >= 2 && foodPool.all { "FOOD" in it.categories })

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
            val food = client.wholePool(player, "&$category=${"FOOD"}").ids()
            val lifestyle = client.wholePool(player, "&$category=${"LIFESTYLE"}").ids()
            assertTrue(food.any { it in lifestyle }, "no question is filed under both")

            val either =
                client.wholePool(
                    player,
                    "&$category=${"FOOD"}&$category=${"LIFESTYLE"}",
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
            assertEquals(
                tallyOf(question.id, votesA = 0, votesB = 1),
                answered.tally,
                "the vote, and nothing for the skips",
            )
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
                    likesReceived = 0,
                    pointsSpent = 0,
                ),
                response.body<PlayerStatsDto>(),
            )
            // Sent even where they equal the contract's defaults, which is all a fresh player has but
            // the due count, so the values above are the server's rather than the decoder's. A guest
            // has no username, sent as null, and no Play Games link, sent as false.
            val sent = response.body<JsonObject>()
            assertEquals(
                setOf(
                    "playerId",
                    "totalPoints",
                    "answersGiven",
                    "questionsAnswered",
                    "cycle",
                    "dueThisCycle",
                    "likesReceived",
                    "pointsSpent",
                    "username",
                    "playGamesLinked",
                ),
                sent.keys,
            )
            assertEquals(JsonNull, sent["username"])
            assertEquals(JsonPrimitive(false), sent["playGamesLinked"])
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
                    categories = listOf("SUPERPOWERS"),
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
                    categories = listOf("SUPERPOWERS"),
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
            val pending = client.submitted(author, SubmitQuestionRequest("Fly", "Swim", listOf("FOOD")))

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
                "blank optionA" to SubmitQuestionRequest("   ", "Swim", listOf("FOOD")),
                "empty optionB" to SubmitQuestionRequest("Fly", "", listOf("FOOD")),
                "optionA too long" to SubmitQuestionRequest(tooLong, "Swim", listOf("FOOD")),
                "optionB too long" to SubmitQuestionRequest("Fly", tooLong, listOf("FOOD")),
                "the same options ignoring case" to SubmitQuestionRequest("Fly", "fLY", listOf("FOOD")),
                "the same options once trimmed" to
                    SubmitQuestionRequest("  Fly ", "fly\n", listOf("FOOD")),
                // PostgreSQL refuses a NUL in text, which H2 stores, so only the check can catch it. Neither
                // a NUL nor a DEL is whitespace, so trimming leaves one at either end for the check.
                "trailing NUL in optionA" to SubmitQuestionRequest("Fly\u0000", "Swim", listOf("FOOD")),
                "leading DEL in optionB" to SubmitQuestionRequest("Fly", "\u007FSwim", listOf("FOOD")),
                "newline inside optionB" to SubmitQuestionRequest("Fly", "Swim\nfast", listOf("FOOD")),
                "tab inside optionA" to SubmitQuestionRequest("Fly\thigh", "Swim", listOf("FOOD")),
                // Line breaks that are not control characters: text layout still breaks the line at each.
                "line separator inside optionA" to
                    SubmitQuestionRequest("Fly\u2028high", "Swim", listOf("FOOD")),
                "paragraph separator inside optionB" to
                    SubmitQuestionRequest("Fly", "Swim\u2029fast", listOf("FOOD")),
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
                "at the limit" to SubmitQuestionRequest(longest, "Swim", listOf("FOOD")),
                "at the limit once trimmed" to SubmitQuestionRequest("Fly", padded, listOf("FOOD")),
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
            // Registered, since a guest is refused before anything it sends is checked.
            val author = client.registered()

            listOf(
                "no category" to """{"optionA":"Fly","optionB":"Swim"}""",
                "an empty list of categories" to """{"optionA":"Fly","optionB":"Swim","categories":[]}""",
                "the single category of old" to """{"optionA":"Fly","optionB":"Swim","category":"FOOD"}""",
                "categories not a list" to """{"optionA":"Fly","optionB":"Swim","categories":"FOOD"}""",
                "UNKNOWN category" to """{"optionA":"Fly","optionB":"Swim","categories":["UNKNOWN"]}""",
                "UNKNOWN beside a real category" to
                    """{"optionA":"Fly","optionB":"Swim","categories":["FOOD","UNKNOWN"]}""",
                "unrecognised category" to """{"optionA":"Fly","optionB":"Swim","categories":["FROM_THE_FUTURE"]}""",
                // What an installed build still offers: RANDOM went away with V6.
                "RANDOM" to """{"optionA":"Fly","optionB":"Swim","categories":["RANDOM"]}""",
                // Refused whole rather than filed under FOOD alone.
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
    fun `a submission is filed under every category it names once each and in the order of categories`() =
        runServer("submit-categories") { client ->
            val author = client.guest()
            val named = listOf("SUPERPOWERS", "FOOD", "SUPERPOWERS")

            val submission = client.submitted(author, SubmitQuestionRequest("Fly", "Swim", named))

            assertEquals(listOf("FOOD", "SUPERPOWERS"), submission.categories)
            assertEquals(listOf(submission), client.mySubmissions(author), "and listed as it was answered")
        }

    @Test
    fun `a guest cannot submit whatever the submission holds, and can once registered`() =
        runServer("submit-guest") { client ->
            val guest = client.guest()
            grantPoints(guest.playerId, Scoring.DEFAULT_SUBMISSION_COST)

            listOf(
                "a question the rules take" to question("Fly"),
                // Each of these would be refused for what it holds, but a guest is refused first.
                "the same option twice" to SubmitQuestionRequest("Fly", "fly", listOf("FOOD")),
                "a category no category has" to SubmitQuestionRequest("Fly", "Swim", listOf("NO_SUCH_CATEGORY")),
            ).forEach { (case, request) ->
                // Straight with the token, so nothing registers the guest first.
                val response = client.submit(guest.accessToken, request)

                assertEquals(HttpStatusCode.Forbidden, response.status, case)
                assertEquals(ErrorCode.ACCOUNT_REQUIRED, response.body<ErrorDto>().code, case)
            }
            assertEquals(emptyList(), client.mySubmissions(guest), "nothing stored")
            assertEquals(Scoring.DEFAULT_SUBMISSION_COST, client.stats(guest).totalPoints, "and nothing taken")

            val registered =
                client.post(WyrApi.Paths.AUTH_REGISTER) {
                    bearerAuth(guest.accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(RegisterRequest("guestnomore", "a password"))
                }
            assertEquals(HttpStatusCode.OK, registered.status)

            assertEquals(HttpStatusCode.Created, client.submit(guest.accessToken, question("Fly")).status, "registered")
        }

    @Test
    fun `submitting costs a point which a rejection pays back and an approval keeps`() =
        runServer("submit-cost") { client ->
            val author = client.registered()
            // Straight with the token, so nothing gives the author the points first.
            val refused = client.submit(author.accessToken, question("Broke"))
            assertEquals(HttpStatusCode.Conflict, refused.status)
            assertEquals(ErrorCode.NOT_ENOUGH_POINTS, refused.body<ErrorDto>().code)
            assertEquals(emptyList(), client.mySubmissions(author), "nothing stored")

            repeat(2) { client.vote(author, "seed-1", OptionSide.A) }
            val (kept, paidBack) =
                listOf("Kept", "Paid back").map { text ->
                    client.submit(author.accessToken, question(text)).body<SubmissionDto>()
                }
            assertEquals(0, client.stats(author).totalPoints, "two answers paid for two submissions")
            assertEquals(2 * Scoring.DEFAULT_SUBMISSION_COST, client.stats(author).pointsSpent)
            assertEquals(HttpStatusCode.Conflict, client.submit(author.accessToken, question("Broke again")).status)

            client.approve(kept.id)
            client.reject(paidBack.id, "No")

            val stats = client.stats(author)
            assertEquals(Scoring.DEFAULT_SUBMISSION_COST, stats.totalPoints, "the rejected one's cost is back")
            assertEquals(Scoring.DEFAULT_SUBMISSION_COST, stats.pointsSpent, "the approved one's is kept")
        }

    @Test
    fun `a player may have only so many submissions pending at once`() =
        runServer("submit-limit") { client ->
            val author = client.guest()
            repeat(WyrApi.Limits.MAX_PENDING_SUBMISSIONS) { index ->
                val request = SubmitQuestionRequest("Option $index", "Other $index", listOf("ABSURD"))
                assertEquals(HttpStatusCode.Created, client.submit(author, request).status, "submission ${index + 1}")
            }

            val refused =
                client.submit(
                    author,
                    SubmitQuestionRequest("One", "Too many", listOf("ABSURD")),
                )

            assertEquals(HttpStatusCode.Conflict, refused.status)
            assertEquals(ErrorCode.SUBMISSION_LIMIT, refused.body<ErrorDto>().code)
            assertEquals(WyrApi.Limits.MAX_PENDING_SUBMISSIONS, client.mySubmissions(author).size)
            assertEquals(
                HttpStatusCode.Created,
                client
                    .submit(
                        client.guest(),
                        SubmitQuestionRequest("One", "Too many", listOf("ABSURD")),
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
                        SubmitQuestionRequest("A $index", "B $index", listOf("FOOD")),
                    )
                }
            val theirs =
                client.submitted(
                    other,
                    SubmitQuestionRequest("Theirs", "Not ours", listOf("ETHICS")),
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
                    setBody(SubmitQuestionRequest("Fly", "Swim", listOf("FOOD")))
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ErrorCode.UNAUTHORIZED, response.body<ErrorDto>().code)
        }

    @Test
    fun `a validly signed token for a player that does not exist cannot submit`() =
        runServer("submit-ghost-player") { client ->
            // As for the ghost-player vote, the helper's token for a real player has to pass first.
            val real = client.registered()
            val request = SubmitQuestionRequest("Fly", "Swim", listOf("FOOD"))
            grantPoints(real.playerId, Scoring.DEFAULT_SUBMISSION_COST)
            assertEquals(HttpStatusCode.Created, client.submit(signAccessToken(real.playerId), request).status)

            val response = client.submit(signAccessToken("no-such-player"), request)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ErrorCode.UNAUTHORIZED, response.body<ErrorDto>().code)
        }

    @Test
    fun `the moderator's queue lists every player's pending submissions oldest first naming each author by id`() =
        runServer("admin-queue") { client ->
            val (author, other) = client.guest() to client.guest()
            val submitted =
                listOf(author, other, author).mapIndexed { index, session ->
                    client
                        .submitted(
                            session,
                            SubmitQuestionRequest("A $index", "B $index", listOf("FOOD")),
                        ).copy(authorId = session.playerId)
                }

            val response = client.moderatorQueue()

            val queue = response.body<SubmissionListDto>().submissions
            assertEquals(submitted.sortedWith(compareBy({ it.submittedAt }, { it.id })), queue, "as each was answered")
            assertEquals(queue, client.queue("?${WyrApi.Query.STATUS}=PENDING"), "which is the default")
            assertEquals(queue.take(2), client.queue("?${WyrApi.Query.LIMIT}=2"), "the head of the queue")
            assertEquals(
                setOf(
                    "id",
                    "optionA",
                    "optionB",
                    "categories",
                    "status",
                    "rejectionReason",
                    "submittedAt",
                    "likeCount",
                    "dislikeCount",
                    "answerCount",
                    "authorId",
                ),
                response
                    .body<JsonObject>()
                    .getValue("submissions")
                    .jsonArray
                    .first()
                    .jsonObject.keys,
                "as its author sees it, and who that is by an opaque id alone",
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
            val pending = client.submitted(author, SubmitQuestionRequest("Fly", "Swim", listOf("FOOD")))
            val seeds = client.wholePool(finished)
            seeds.forEach { seed -> client.vote(finished, seed.id, OptionSide.A) }
            client.vote(midway, seeds.first().id, OptionSide.A)
            assertEquals(0, client.stats(finished).dueThisCycle, "the cycle is finished")

            val response = client.approve(pending.id)

            assertEquals(HttpStatusCode.OK, response.status)
            val approved = response.body<SubmissionDto>()
            assertEquals(pending.copy(status = QuestionStatus.APPROVED, authorId = author.playerId), approved)
            assertEquals(
                listOf(approved.copy(authorId = null)),
                client.mySubmissions(author),
                "and its author sees it so, named nowhere",
            )
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
            assertEquals(
                listOf(approved.copy(answerCount = 1)),
                client.queue("?${WyrApi.Query.STATUS}=APPROVED"),
                "its author's answer counted",
            )
        }

    @Test
    fun `a rejected submission is served to nobody and its author sees why`() =
        runServer("admin-reject") { client ->
            val (author, player) = client.guest() to client.guest()
            val pending = client.submitted(author, SubmitQuestionRequest("Fly", "Swim", listOf("FOOD")))

            val response = client.reject(pending.id, "  Not really a dilemma ")

            assertEquals(HttpStatusCode.OK, response.status)
            val rejected = response.body<SubmissionDto>()
            assertEquals(
                pending.copy(
                    status = QuestionStatus.REJECTED,
                    rejectionReason = "Not really a dilemma",
                    authorId = author.playerId,
                ),
                rejected,
                "its reason stored trimmed",
            )
            assertEquals(listOf(rejected.copy(authorId = null)), client.mySubmissions(author))
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
            val pending = client.submitted(author, SubmitQuestionRequest("Fly", "Swim", listOf("FOOD")))
            val named = listOf("SUPERPOWERS", "ETHICS", "SUPERPOWERS")

            val approved = client.approve(pending.id, named).body<SubmissionDto>()

            val chosen = listOf("ETHICS", "SUPERPOWERS")
            assertEquals(chosen, approved.categories, "each once, in the order of categories")
            assertEquals(
                listOf(approved.copy(authorId = null)),
                client.mySubmissions(author),
                "and its author sees them",
            )
            assertEquals(chosen, client.wholePool(player).single { it.id == pending.id }.categories)
            assertFalse(pending.id in client.wholePool(player, "&category=FOOD").ids(), "no longer filed under food")
            listOf("ETHICS", "SUPERPOWERS").forEach { category ->
                assertTrue(pending.id in client.wholePool(player, "&category=$category").ids(), category)
            }

            // Naming none keeps the author's.
            val kept = client.submitted(author, SubmitQuestionRequest("Run", "Walk", listOf("ABSURD")))
            assertEquals(listOf("ABSURD"), client.approve(kept.id).body<SubmissionDto>().categories)
        }

    @Test
    fun `a decision on a question that is not pending is a conflict and changes nothing`() =
        runServer("admin-conflict") { client ->
            val author = client.guest()
            val approved = client.approve(client.submitted(author, question("Approved")).id).body<SubmissionDto>()
            val rejected = client.reject(client.submitted(author, question("Rejected")).id, "No").body<SubmissionDto>()

            listOf("approved" to approved.id, "rejected" to rejected.id, "a seed" to "seed-1").forEach { (case, id) ->
                listOf(
                    "approving" to client.approve(id, listOf("ETHICS")),
                    "rejecting" to client.reject(id, "Changed my mind"),
                ).forEach { (decision, response) ->
                    assertEquals(HttpStatusCode.Conflict, response.status, "$decision $case")
                    assertEquals(ErrorCode.ALREADY_DECIDED, response.body<ErrorDto>().code, "$decision $case")
                }
            }

            assertEquals(
                setOf(approved, rejected).map { it.copy(authorId = null) }.toSet(),
                client.mySubmissions(author).toSet(),
                "as first decided",
            )
            val seed = client.wholePool(author).single { it.id == "seed-1" }
            assertFalse("ETHICS" in seed.categories, "a seed's categories kept too")
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
            val author = client.guest()
            val pending = client.submitted(author, question("Pending")).copy(authorId = author.playerId)
            val id = pending.id
            val tooLong = "x".repeat(WyrApi.Limits.MAX_REJECTION_REASON_LENGTH + 1)
            val approvals = WyrApi.Paths.ADMIN_APPROVALS
            val rejections = WyrApi.Paths.ADMIN_REJECTIONS

            listOf(
                "an approval with no questionId" to (approvals to """{"categories":["FOOD"]}"""),
                "an approval of a blank id" to (approvals to """{"questionId":"  "}"""),
                "an approval of an id with a NUL" to (approvals to """{"questionId":"$id\u0000"}"""),
                "an approval naming UNKNOWN" to (approvals to """{"questionId":"$id","categories":["UNKNOWN"]}"""),
                "an approval naming RANDOM" to (approvals to """{"questionId":"$id","categories":["RANDOM"]}"""),
                // Refused whole rather than filed under FOOD alone.
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
            val author = client.guest()
            val pending = client.submitted(author, question("Pending")).copy(authorId = author.playerId)

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

    @Test
    fun `the moderator's list holds every question with its numbers, pages through them, naming authors by id`() =
        runServer("admin-questions") { client ->
            val (author, player) = client.guest() to client.guest()
            val pending = client.submitted(author, question("Pending"))
            val approved = client.approvedQuestion(author, "Approved")
            client.vote(player, approved.id, OptionSide.B)
            client.reacted(player, approved.id, Reaction.LIKE)
            client.reacted(author, approved.id, Reaction.DISLIKE)
            val seeds = client.wholePool(author).ids().filterNot { it == approved.id }

            val response = client.adminQuestions("?${WyrApi.Query.LIMIT}=${WyrApi.Limits.MAX_PAGE_SIZE}")

            assertEquals(HttpStatusCode.OK, response.status)
            val listed = response.body<AdminQuestionPageDto>()
            assertEquals(null, listed.nextCursor, "all of them on one page")
            val byId = listed.questions.associateBy { it.id }
            assertEquals((seeds + pending.id + approved.id).toSet(), byId.keys)
            assertEquals(
                seeds.toSet(),
                listed.questions
                    .filter { it.seed }
                    .map { it.id }
                    .toSet(),
            )
            assertEquals(
                listed.questions.sortedByDescending { it.submittedAt },
                listed.questions,
                "newest first; the order of a tie is the database's",
            )
            assertEquals(QuestionStatus.PENDING, byId.getValue(pending.id).status)
            assertEquals(
                listOf(author.playerId),
                listOf(pending, approved).map { byId.getValue(it.id).authorId }.distinct(),
            )
            assertEquals(setOf(null), seeds.map { byId.getValue(it).authorId }.toSet(), "a seed has no author")
            val numbers =
                byId
                    .getValue(
                        approved.id,
                    ).let { it.status to Triple(it.tally, it.likeCount, it.dislikeCount) }
            assertEquals(QuestionStatus.APPROVED to Triple(VoteTallyDto(votesA = 0, votesB = 1), 1, 1), numbers)
            assertEquals(
                ADMIN_QUESTION_FIELDS,
                response
                    .body<JsonObject>()
                    .getValue("questions")
                    .jsonArray
                    .first()
                    .jsonObject.keys,
                "who wrote it by an opaque id alone",
            )

            val paged = mutableListOf(client.adminQuestionPage("?${WyrApi.Query.LIMIT}=5"))
            while (true) {
                val cursor = paged.last().nextCursor ?: break
                paged += client.adminQuestionPage("?${WyrApi.Query.LIMIT}=5&${WyrApi.Query.CURSOR}=$cursor")
            }
            assertEquals(listed.questions, paged.flatMap { it.questions }, "the same list, five at a time")

            val status = WyrApi.Query.STATUS
            assertEquals(listOf(pending.id), client.adminQuestionIds("?$status=PENDING"))
            assertEquals(
                (seeds + approved.id).toSet(),
                client.adminQuestionIds("?$status=APPROVED&$status=REJECTED").toSet(),
            )
            assertEquals(
                listOf(approved.id, pending.id).toSet(),
                client.adminQuestionIds("?$status=PENDING&$status=APPROVED&${WyrApi.Query.CATEGORY}=FOOD").toSet() -
                    seeds.toSet(),
                "a category filter as well",
            )
        }

    @Test
    fun `the moderator's list refuses a filter, a cursor or a limit it cannot use`() =
        runServer("admin-questions-malformed") { client ->
            val (status, category, cursor) = Triple(WyrApi.Query.STATUS, WyrApi.Query.CATEGORY, WyrApi.Query.CURSOR)

            listOf(
                "the status UNKNOWN" to "?$status=UNKNOWN",
                "an unrecognised status" to "?$status=FROM_THE_FUTURE",
                "a status in lower case" to "?$status=pending",
                "an empty status" to "?$status=",
                "comma-separated statuses" to "?$status=PENDING,REJECTED",
                "the category UNKNOWN" to "?$category=UNKNOWN",
                "an unrecognised category" to "?$category=FROM_THE_FUTURE",
                "a cursor the server did not make" to "?$cursor=not-a-cursor",
                "a cursor with no time" to "?$cursor=:seed-1",
                "a cursor with no id" to "?$cursor=1790000000000:",
                "a cursor with a blank id" to "?$cursor=1790000000000:%20",
                "two cursors" to "?$cursor=1790000000000:seed-1&$cursor=1790000000000:seed-2",
                "a limit that is not a number" to "?${WyrApi.Query.LIMIT}=many",
            ).forEach { (case, query) ->
                val response = client.adminQuestions(query)
                assertEquals(HttpStatusCode.BadRequest, response.status, case)
                assertEquals(ErrorCode.VALIDATION_FAILED, response.body<ErrorDto>().code, case)
            }
        }

    @Test
    fun `a retired question is served to nobody, keeps what it earned, and is served again once restored`() =
        runServer("admin-retire") { client ->
            val (author, player) = client.guest() to client.guest()
            val question = client.approvedQuestion(author, "Retired")
            client.vote(player, question.id, OptionSide.B)
            client.reacted(player, question.id, Reaction.LIKE)

            val response = client.retire(question.id)

            assertEquals(HttpStatusCode.OK, response.status)
            val retired = response.body<AdminQuestionDto>()
            assertEquals(QuestionStatus.RETIRED, retired.status)
            assertTrue(retired.retiredAt != null, "when it was retired")
            assertEquals(VoteTallyDto(votesA = 0, votesB = 1) to 1, retired.tally to retired.likeCount, "all kept")
            listOf("its author" to author, "another player" to player).forEach { (who, session) ->
                assertFalse(question.id in client.wholePool(session).ids(), "$who is not served it")
                val refusals =
                    listOf(
                        "an answer" to client.castVote(session, VoteRequest(question.id, OptionSide.A, "attempt-2")),
                        "a skip" to client.skip(session, question.id),
                        "a like" to client.react(session, question.id, Reaction.LIKE),
                        "a dislike" to client.react(session, question.id, Reaction.DISLIKE),
                        "taking one back" to client.react(session, question.id, Reaction.NONE),
                    )
                refusals.forEach { (what, refusal) ->
                    assertEquals(HttpStatusCode.NotFound, refusal.status, "$what by $who")
                    assertEquals(ErrorCode.QUESTION_NOT_FOUND, refusal.body<ErrorDto>().code, "$what by $who")
                }
            }
            assertEquals(QuestionStatus.RETIRED, client.mySubmissions(author).single().status, "its author sees it")
            client.stats(author).let { stats ->
                assertEquals(1 to 1, stats.likesReceived to stats.totalPoints, "the like still held, and paid")
            }
            assertEquals(listOf(retired), client.adminQuestionPage("?${WyrApi.Query.STATUS}=RETIRED").questions)
            assertEquals(listOf(question.id), client.queue("?${WyrApi.Query.STATUS}=RETIRED").map { it.id })
            assertFalse(question.id in client.queue("?${WyrApi.Query.STATUS}=APPROVED").map { it.id })

            val restored = client.restore(question.id)

            assertEquals(HttpStatusCode.OK, restored.status)
            assertEquals(
                retired.copy(status = QuestionStatus.APPROVED, retiredAt = null),
                restored.body<AdminQuestionDto>(),
            )
            assertEquals(QuestionStatus.APPROVED, client.mySubmissions(author).single().status)
            assertTrue(question.id in client.wholePool(author).ids(), "due again for one who has not answered it")
            assertEquals(Scoring.POINTS_PER_ANSWER, client.vote(author, question.id, OptionSide.A).pointsAwarded)
        }

    @Test
    fun `retiring a question that is not approved, or restoring one that is not retired, is a conflict`() =
        runServer("admin-retire-conflict") { client ->
            val author = client.guest()
            val pending = client.submitted(author, question("Pending"))
            val rejected = client.reject(client.submitted(author, question("Rejected")).id, "No").body<SubmissionDto>()
            val approved = client.approvedQuestion(author, "Approved")
            val retired = client.approvedQuestion(author, "Retired").also { client.retire(it.id) }

            listOf(
                "retiring a pending one" to client.retire(pending.id),
                "retiring a rejected one" to client.retire(rejected.id),
                "retiring a retired one" to client.retire(retired.id),
                "restoring a pending one" to client.restore(pending.id),
                "restoring a rejected one" to client.restore(rejected.id),
                "restoring an approved one" to client.restore(approved.id),
                "restoring a seed" to client.restore("seed-1"),
            ).forEach { (case, response) ->
                assertEquals(HttpStatusCode.Conflict, response.status, case)
                assertEquals(ErrorCode.WRONG_STATUS, response.body<ErrorDto>().code, case)
            }
            listOf("retiring" to client.retire(LONGER_THAN_ANY_ID), "restoring" to client.restore("no-such-question"))
                .forEach { (case, response) ->
                    assertEquals(HttpStatusCode.NotFound, response.status, case)
                    assertEquals(ErrorCode.QUESTION_NOT_FOUND, response.body<ErrorDto>().code, case)
                }
            // By id, not by position: submissions made within one millisecond list in id order.
            assertEquals(
                mapOf(
                    retired.id to QuestionStatus.RETIRED,
                    approved.id to QuestionStatus.APPROVED,
                    rejected.id to QuestionStatus.REJECTED,
                    pending.id to QuestionStatus.PENDING,
                ),
                client.mySubmissions(author).associate { it.id to it.status },
                "each as it was",
            )

            val seed = client.retire("seed-1").body<AdminQuestionDto>()
            assertEquals(QuestionStatus.RETIRED to true, seed.status to seed.seed, "a seed retires like any other")
        }

    @Test
    fun `a retirement or restoration the server cannot use is a validation error`() =
        runServer("admin-retire-malformed") { client ->
            val approved = client.approvedQuestion(client.guest(), "Approved")

            listOf(WyrApi.Paths.ADMIN_RETIREMENTS, WyrApi.Paths.ADMIN_RESTORATIONS).forEach { path ->
                listOf(
                    "no questionId" to "{}",
                    "a blank id" to """{"questionId":"  "}""",
                    "an id with a NUL" to """{"questionId":"${approved.id}\u0000"}""",
                    "not json" to "{not json",
                ).forEach { (case, body) ->
                    val response = client.moderate(path, body)
                    assertEquals(HttpStatusCode.BadRequest, response.status, "$case to $path")
                    assertEquals(ErrorCode.VALIDATION_FAILED, response.body<ErrorDto>().code, "$case to $path")
                }
            }
            val stillApproved =
                client
                    .adminQuestionPage(
                        "?${WyrApi.Query.STATUS}=APPROVED&${WyrApi.Query.LIMIT}=${WyrApi.Limits.MAX_PAGE_SIZE}",
                    ).questions
                    .filterNot { it.seed }
            assertEquals(listOf(approved.id), stillApproved.map { it.id }, "nothing was retired")
        }

    @Test
    fun `a reaction is set rather than toggled and a like pays the author a point for as long as it is held`() =
        runServer("reaction") { client ->
            val (author, fan) = client.guest() to client.guest()
            val question = client.approvedQuestion(author, "Fly")

            val response = client.react(fan, question.id, Reaction.LIKE)

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(result(question.id, likes = 1, mine = Reaction.LIKE), response.body<ReactionResultDto>())
            assertEquals(
                result(question.id, likes = 1, mine = Reaction.LIKE),
                client.reacted(fan, question.id, Reaction.LIKE),
                "liking again changes nothing",
            )
            val paid = client.stats(author)
            assertEquals(Scoring.POINTS_PER_LIKE, paid.totalPoints, "paid once")
            assertEquals(1, paid.likesReceived)
            assertEquals(0, client.stats(fan).totalPoints, "and the fan nothing")

            val forFan = client.wholePool(fan).single { it.id == question.id }
            assertEquals(1 to Reaction.LIKE, forFan.likeCount to forFan.myReaction, "shown before the fan answers it")
            assertFalse(forFan.answeredBefore)
            val forAuthor = client.wholePool(author).single { it.id == question.id }
            assertEquals(1 to Reaction.NONE, forAuthor.likeCount to forAuthor.myReaction)

            assertEquals(result(question.id), client.reacted(fan, question.id, Reaction.NONE))
            assertEquals(result(question.id), client.reacted(fan, question.id, Reaction.NONE), "taking none back again")
            val unpaid = client.stats(author)
            assertEquals(0, unpaid.totalPoints, "the point went with the like, once")
            assertEquals(0, unpaid.likesReceived)
        }

    @Test
    fun `a dislike replaces a like, takes its point back and pays nobody anything`() =
        runServer("dislike") { client ->
            val (author, player) = client.guest() to client.guest()
            val question = client.approvedQuestion(author, "Fly")
            client.reacted(player, question.id, Reaction.LIKE)

            assertEquals(
                result(question.id, dislikes = 1, mine = Reaction.DISLIKE),
                client.reacted(player, question.id, Reaction.DISLIKE),
            )
            assertEquals(
                0 to 0,
                client.stats(author).let { it.totalPoints to it.likesReceived },
                "the like's point went",
            )
            assertEquals(0, client.stats(player).totalPoints)
            val forPlayer = client.wholePool(player).single { it.id == question.id }
            assertEquals(
                Triple(0, 1, Reaction.DISLIKE),
                Triple(forPlayer.likeCount, forPlayer.dislikeCount, forPlayer.myReaction),
            )

            assertEquals(
                result(question.id, likes = 1, mine = Reaction.LIKE),
                client.reacted(player, question.id, Reaction.LIKE),
            )
            assertEquals(Scoring.POINTS_PER_LIKE, client.stats(author).totalPoints, "and back again")
        }

    @Test
    fun `an author may react to their own question and a seed's reactions pay nobody`() =
        runServer("reaction-own-and-seed") { client ->
            val (author, other) = client.guest() to client.guest()
            val own = client.approvedQuestion(author, "Fly")

            assertEquals(result(own.id, likes = 1, mine = Reaction.LIKE), client.reacted(author, own.id, Reaction.LIKE))
            assertEquals(
                result("seed-1", likes = 1, mine = Reaction.LIKE),
                client.reacted(author, "seed-1", Reaction.LIKE),
            )
            assertEquals(
                result("seed-1", likes = 1, dislikes = 1, mine = Reaction.DISLIKE),
                client.reacted(other, "seed-1", Reaction.DISLIKE),
            )

            val stats = client.stats(author)
            assertEquals(Scoring.POINTS_PER_LIKE, stats.totalPoints, "for their own question, not for the seed")
            assertEquals(1, stats.likesReceived)
            assertEquals(0, client.stats(other).totalPoints)
        }

    @Test
    fun `reacting to a question that does not exist or is not approved is a not-found`() =
        runServer("reaction-missing-question") { client ->
            val (author, player) = client.guest() to client.guest()
            val pending = client.submitted(author, question("Pending"))
            val rejected = client.submitted(author, question("Rejected"))
            assertEquals(HttpStatusCode.OK, client.reject(rejected.id, "No").status)
            val before = client.stats(author).totalPoints

            listOf("no-such-question", LONGER_THAN_ANY_ID, pending.id, rejected.id).forEach { questionId ->
                listOf(author, player).forEach { session ->
                    Reaction.entries.forEach { reaction ->
                        val response = client.react(session, questionId, reaction)

                        assertEquals(HttpStatusCode.NotFound, response.status, "$questionId given $reaction")
                        assertEquals(ErrorCode.QUESTION_NOT_FOUND, response.body<ErrorDto>().code, questionId)
                    }
                }
            }
            assertEquals(before, client.stats(author).totalPoints, "none of them paid the author")
        }

    @Test
    fun `reacting needs a session`() =
        runServer("reaction-no-token") { client ->
            val response =
                client.post(WyrApi.Paths.REACTIONS) {
                    contentType(ContentType.Application.Json)
                    setBody(ReactionRequest("seed-1", Reaction.LIKE))
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ErrorCode.UNAUTHORIZED, response.body<ErrorDto>().code)
        }

    @Test
    fun `a validly signed token for a player that does not exist cannot react`() =
        runServer("reaction-ghost-player") { client ->
            // As for the ghost-player vote, the helper's token for a real player has to pass first.
            val real = client.guest()
            assertEquals(
                HttpStatusCode.OK,
                client.react(signAccessToken(real.playerId), "seed-1", Reaction.LIKE).status,
            )

            val response = client.react(signAccessToken("no-such-player"), "seed-1", Reaction.LIKE)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(ErrorCode.UNAUTHORIZED, response.body<ErrorDto>().code)
        }

    @Test
    fun `a reaction body the server cannot use is a validation error`() =
        runServer("malformed-reaction") { client ->
            val session = client.guest()

            listOf(
                "malformed json" to "{not json",
                "no questionId" to """{"reaction":"LIKE"}""",
                // A reaction is set rather than toggled, so a request must say which.
                "no reaction" to """{"questionId":"seed-1"}""",
                "null reaction" to """{"questionId":"seed-1","reaction":null}""",
                // The enum is closed: no member to read an unknown one as.
                "unknown reaction" to """{"questionId":"seed-1","reaction":"LOVE"}""",
                "blank questionId" to """{"questionId":"  ","reaction":"LIKE"}""",
                // PostgreSQL refuses a NUL in text, which H2 stores, so only the check can catch it.
                "NUL in questionId" to """{"questionId":"seed-1\u0000","reaction":"LIKE"}""",
                "newline in questionId" to """{"questionId":"seed-1\n","reaction":"LIKE"}""",
            ).forEach { (case, body) ->
                val response =
                    client.post(WyrApi.Paths.REACTIONS) {
                        bearerAuth(session.accessToken)
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status, case)
                assertEquals(ErrorCode.VALIDATION_FAILED, response.body<ErrorDto>().code, case)
            }
            val seed = client.wholePool(session).single { it.id == "seed-1" }
            assertEquals(0 to 0, seed.likeCount to seed.dislikeCount, "and nobody reacted")
        }

    /** What a reaction to [questionId] answers, with these counts and the player's own [mine]. */
    private fun result(
        questionId: String,
        likes: Int = 0,
        dislikes: Int = 0,
        mine: Reaction = Reaction.NONE,
    ) = ReactionResultDto(questionId, likeCount = likes, dislikeCount = dislikes, myReaction = mine)

    /**
     * A server on [database], its own unless a test that reaches into it passes one, moderated with
     * [adminToken], or with moderation off for none, and with production's refresh grace, which has no
     * time bound, unless a test sets one.
     */
    private fun runServer(
        databaseName: String,
        adminToken: String? = TEST_ADMIN_TOKEN,
        refreshGraceSeconds: Long? = null,
        database: TestDatabaseSettings = testDatabaseFor(databaseName),
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) = testApplication {
        serverDatabase = database
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
                // Production's 30 days, so a test can stamp a rotation hours ago well within them.
                refreshTokenTtlSeconds = 30.days.inWholeSeconds,
                refreshGraceSeconds = refreshGraceSeconds,
                allowedWebOrigins = emptyList(),
                adminToken = adminToken,
                rateLimits = NO_PRACTICAL_LIMIT,
                clientIpHeader = null,
                onRender = false,
            )

        application { wyrModule(config, TEST_SEEDS) }

        val client =
            createClient {
                // Left at the default (false) so a test can assert on a failure status directly.
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }

        block(client)
    }

    /**
     * Restamps the last refresh-token rotation of [playerId]'s one session as [ago] before now, as
     * though it had happened then: a test cannot wait that long. Of the token that rotation displaced,
     * only a bound on the grace reads the stamp; its expiry is left where it was.
     */
    private fun TestDatabaseSettings.stampLastRotation(
        playerId: String,
        ago: Duration,
    ) = serverPool().use { pool ->
        pool.inTransaction {
            val restamped =
                Sessions.update({ Sessions.playerId eq playerId }) { row ->
                    row[previousRefreshTokenRotatedAt] = System.currentTimeMillis() - ago.inWholeMilliseconds
                }
            check(restamped == 1) { "no one session for player $playerId" }
        }
    }

    /**
     * Drops the players row's seven unused columns, and the unique constraints three of them hold, as
     * the later migration will (CLAUDE.md §8b, *Rollbacks*).
     */
    private fun TestDatabaseSettings.dropUnusedPlayersColumns() =
        serverPool().use { pool ->
            pool.inTransaction {
                listOf(
                    "players_refresh_token_hash_unique",
                    "players_previous_refresh_token_hash_unique",
                    "players_recovery_secret_hash_unique",
                ).forEach { constraint -> exec("ALTER TABLE players DROP CONSTRAINT $constraint") }
                listOf(
                    "refresh_token_hash",
                    "refresh_token_expires_at",
                    "previous_refresh_token_hash",
                    "previous_refresh_token_expires_at",
                    "previous_refresh_token_rotated_at",
                    "mirrored_refresh_token_hash",
                    "recovery_secret_hash",
                ).forEach { column -> exec("ALTER TABLE players DROP COLUMN $column") }
            }
        }

    private suspend fun HttpClient.guest(): SessionDto = post(WyrApi.Paths.AUTH_GUEST).body()

    /** A fresh guest, registered through the API under a name of its own. */
    private suspend fun HttpClient.registered(): SessionDto =
        guest().also { session ->
            val name = "p" + session.playerId.replace("-", "").take(12)
            val response =
                post(WyrApi.Paths.AUTH_REGISTER) {
                    bearerAuth(session.accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(RegisterRequest(name, "a password"))
                }
            assertEquals(HttpStatusCode.OK, response.status, "registering $name")
        }

    private suspend fun HttpClient.refresh(refreshToken: String): HttpResponse =
        post(WyrApi.Paths.AUTH_REFRESH) {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(refreshToken))
        }

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

    /**
     * Submits [request] as [session]'s player, given the points it costs first (CLAUDE.md §8c), so the
     * player's total ends where it began, and an account first if a guest, since only a registered
     * player may submit (§8d, *Submitting*): what a test submits for when it is about neither.
     */
    private suspend fun HttpClient.submit(
        session: SessionDto,
        request: SubmitQuestionRequest,
    ): HttpResponse {
        grantPoints(session.playerId, Scoring.DEFAULT_SUBMISSION_COST)
        registerIfGuest(session.playerId)
        return submit(session.accessToken, request)
    }

    /**
     * Gives [playerId] an account straight in the database of the server under test, unless it has
     * one: a name made from its id, and a hash no password matches, since nothing here logs in as it.
     */
    private fun registerIfGuest(playerId: String) =
        checkNotNull(serverDatabase) { "no server is running" }.serverPool().use { pool ->
            pool.inTransaction {
                if (PlayerStore.find(playerId)?.username == null) {
                    AccountStore.register(playerId, "p" + playerId.replace("-", "").take(12), "not-a-password-hash")
                }
            }
        }

    /** Adds [points] to [playerId]'s total straight in the database of the server under test. */
    private fun grantPoints(
        playerId: String,
        points: Int,
    ) = checkNotNull(serverDatabase) { "no server is running" }.serverPool().use { pool ->
        pool.inTransaction { PlayerStore.addPoints(playerId, points) }
    }

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

    /** The moderator's list of every question, asked for with the test server's admin token. */
    private suspend fun HttpClient.adminQuestions(query: String = ""): HttpResponse =
        get(WyrApi.Paths.ADMIN_QUESTIONS + query) { header(WyrApi.Headers.ADMIN_TOKEN, TEST_ADMIN_TOKEN) }

    private suspend fun HttpClient.adminQuestionPage(query: String = ""): AdminQuestionPageDto =
        adminQuestions(query).body()

    /** The ids on one page of the moderator's list, the largest there is, which holds every question here. */
    private suspend fun HttpClient.adminQuestionIds(query: String): List<String> {
        val page = adminQuestionPage("$query&${WyrApi.Query.LIMIT}=${WyrApi.Limits.MAX_PAGE_SIZE}")
        assertEquals(null, page.nextCursor, "the list no longer fits in one page")
        return page.questions.map { it.id }
    }

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
            "the question list" to get(WyrApi.Paths.ADMIN_QUESTIONS) { credentials() },
            "a retirement" to
                post(WyrApi.Paths.ADMIN_RETIREMENTS) {
                    credentials()
                    contentType(ContentType.Application.Json)
                    setBody(RetireQuestionRequest("no-such-question"))
                },
            "a restoration" to
                post(WyrApi.Paths.ADMIN_RESTORATIONS) {
                    credentials()
                    contentType(ContentType.Application.Json)
                    setBody(RestoreQuestionRequest("no-such-question"))
                },
            "a new category" to
                post(WyrApi.Paths.ADMIN_CATEGORIES) {
                    credentials()
                    contentType(ContentType.Application.Json)
                    setBody(CreateCategoryRequest(nameSr = "Животиње", nameEn = "Animals"))
                },
            "a category's names" to
                post(WyrApi.Paths.ADMIN_CATEGORY_RENAMES) {
                    credentials()
                    contentType(ContentType.Application.Json)
                    setBody(RenameCategoryRequest("FOOD", nameSr = "Јело", nameEn = "Meals"))
                },
            "the reports" to get(WyrApi.Paths.ADMIN_REPORTS) { credentials() },
            "a dismissal" to
                post(WyrApi.Paths.ADMIN_REPORT_DISMISSALS) {
                    credentials()
                    contentType(ContentType.Application.Json)
                    setBody(DismissReportsRequest("no-such-question"))
                },
            "an author's block" to
                post(WyrApi.Paths.ADMIN_AUTHOR_BLOCKS) {
                    credentials()
                    contentType(ContentType.Application.Json)
                    setBody(BlockAuthorRequest("no-such-author", reason = "Spam"))
                },
            "an author's unblock" to
                post(WyrApi.Paths.ADMIN_AUTHOR_UNBLOCKS) {
                    credentials()
                    contentType(ContentType.Application.Json)
                    setBody(UnblockAuthorRequest("no-such-author"))
                },
        )

    /** Approves [questionId] with the test server's admin token, filing it under [categories] if any. */
    private suspend fun HttpClient.approve(
        questionId: String,
        categories: List<String> = emptyList(),
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

    private suspend fun HttpClient.retire(questionId: String): HttpResponse =
        post(WyrApi.Paths.ADMIN_RETIREMENTS) {
            header(WyrApi.Headers.ADMIN_TOKEN, TEST_ADMIN_TOKEN)
            contentType(ContentType.Application.Json)
            setBody(RetireQuestionRequest(questionId))
        }

    private suspend fun HttpClient.restore(questionId: String): HttpResponse =
        post(WyrApi.Paths.ADMIN_RESTORATIONS) {
            header(WyrApi.Headers.ADMIN_TOKEN, TEST_ADMIN_TOKEN)
            contentType(ContentType.Application.Json)
            setBody(RestoreQuestionRequest(questionId))
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
    private fun question(text: String) = SubmitQuestionRequest(text, "Not $text", listOf("FOOD"))

    /** A question by [author], submitted and then approved with the test server's admin token. */
    private suspend fun HttpClient.approvedQuestion(
        author: SessionDto,
        text: String,
    ): SubmissionDto {
        val submission = submitted(author, question(text))
        assertEquals(HttpStatusCode.OK, approve(submission.id).status)
        return submission
    }

    private suspend fun HttpClient.reacted(
        session: SessionDto,
        questionId: String,
        reaction: Reaction,
    ): ReactionResultDto = react(session, questionId, reaction).body()

    private suspend fun HttpClient.react(
        session: SessionDto,
        questionId: String,
        reaction: Reaction,
    ): HttpResponse = react(session.accessToken, questionId, reaction)

    private suspend fun HttpClient.react(
        accessToken: String,
        questionId: String,
        reaction: Reaction,
    ): HttpResponse =
        post(WyrApi.Paths.REACTIONS) {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody(ReactionRequest(questionId, reaction))
        }

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
        // No route these tokens reach reads the session, so it need not exist.
        return TokenService(ServerConfig.fromEnvironment(env::get)).issueAccessToken(playerId, "no-such-session")
    }

    private companion object {
        /** The admin token [runServer]'s server is moderated with, unless a test turns moderation off. */
        const val TEST_ADMIN_TOKEN = "test-admin-token-0123456789abcdef"

        /**
         * Longer than the 36 characters of every id column, so no question has it. Nothing on the
         * wire bounds a questionId, so it reaches the lookup and is simply not found.
         */
        const val LONGER_THAN_ANY_ID = "seed-1-and-then-far-more-characters-than-any-question-id-can-hold"

        /** Every field of a question in the moderator's list, as `encodeDefaults` sends each one. */
        val ADMIN_QUESTION_FIELDS =
            setOf(
                "id",
                "optionA",
                "optionB",
                "categories",
                "status",
                "seed",
                "submittedAt",
                "reviewedAt",
                "retiredAt",
                "rejectionReason",
                "tally",
                "likeCount",
                "dislikeCount",
                "authorId",
            )
    }
}
