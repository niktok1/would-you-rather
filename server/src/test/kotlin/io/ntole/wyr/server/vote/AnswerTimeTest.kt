package io.ntole.wyr.server.vote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ntole.wyr.core.api.WyrApi
import io.ntole.wyr.core.auth.SessionDto
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.core.vote.VoteRequest
import io.ntole.wyr.server.TestDatabaseSettings
import io.ntole.wyr.server.db.Seed
import io.ntole.wyr.server.db.TEST_SEEDS
import io.ntole.wyr.server.db.Votes
import io.ntole.wyr.server.db.appTables
import io.ntole.wyr.server.db.connectH2
import io.ntole.wyr.server.db.h2Url
import io.ntole.wyr.server.db.inTransaction
import io.ntole.wyr.server.db.serverPool
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.testDatabaseFor
import io.ntole.wyr.server.testServerConfig
import io.ntole.wyr.server.wyrModule
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** How long an answer took, kept on the vote for choosing questions later (CLAUDE.md §8b, *Personalization*). */
class AnswerTimeTest {
    @Test
    fun `the vote keeps the latest answer's time and a replay leaves it alone`() {
        val database = connectH2(h2Url("wyr-answer-time-${UUID.randomUUID()}"), Connection.TRANSACTION_READ_COMMITTED)
        transaction(database) {
            SchemaUtils.create(*appTables)
            Seed.writeMissing(TEST_SEEDS)
        }
        val player = transaction(database) { PlayerStore.createGuest().id }

        fun cast(
            attempt: String,
            millis: Long?,
        ) = transaction(database) { VoteStore.cast(player, SEED, OptionSide.A, attempt, answerMillis = millis) }

        fun stored(): Long? = transaction(database) { answerMillisOf(player) }

        cast("first", millis = 1_234)
        assertEquals(1_234, stored())

        cast("second", millis = 5_678)
        assertEquals(5_678, stored(), "a re-answer's time replaces the one before")

        cast("second", millis = 9_999)
        assertEquals(5_678, stored(), "a replay writes nothing")

        cast("third", millis = null)
        assertNull(stored(), "an answer that measured none leaves none")
    }

    @Test
    fun `a time from zero to ten minutes is kept and any other is kept as none`() {
        val kept = listOf(0L, 1L, 2_500L, WyrApi.Limits.MAX_ANSWER_MILLIS)
        val dropped = listOf(-1L, Long.MIN_VALUE, WyrApi.Limits.MAX_ANSWER_MILLIS + 1, Long.MAX_VALUE)

        kept.forEach { millis -> assertEquals(millis, keptAnswerMillis(millis), "$millis") }
        dropped.forEach { millis -> assertNull(keptAnswerMillis(millis), "$millis") }
        assertNull(keptAnswerMillis(null))
    }

    @Test
    fun `a vote's answer time reaches the vote and one out of bounds is no refusal`() {
        val database = testDatabaseFor("answer-time-flow")
        testApplication {
            application { wyrModule(testServerConfig(database), TEST_SEEDS) }
            val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val session: SessionDto = client.post(WyrApi.Paths.AUTH_GUEST).body()

            val cases = listOf(3_210L to 3_210L, -5L to null, WyrApi.Limits.MAX_ANSWER_MILLIS + 1 to null, null to null)
            cases.forEachIndexed { index, (sent, kept) ->
                val response =
                    client.vote(
                        session,
                        VoteRequest(SEED, OptionSide.B, "attempt-$index", answerMillis = sent),
                    )

                assertEquals(HttpStatusCode.OK, response.status, "sent $sent")
                assertEquals(kept, database.storedAnswerMillisOf(session.playerId), "sent $sent")
            }
        }
    }

    /** The time [player]'s vote on [SEED] keeps. */
    private fun answerMillisOf(player: String): Long? =
        Votes
            .select(Votes.answerMillis)
            .where { (Votes.playerId eq player) and (Votes.questionId eq SEED) }
            .single()[Votes.answerMillis]

    private fun TestDatabaseSettings.storedAnswerMillisOf(player: String): Long? =
        serverPool().use { pool -> pool.inTransaction { answerMillisOf(player) } }

    private suspend fun HttpClient.vote(
        session: SessionDto,
        request: VoteRequest,
    ) = post(WyrApi.Paths.VOTES) {
        bearerAuth(session.accessToken)
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    private companion object {
        const val SEED = "seed-1"
    }
}
