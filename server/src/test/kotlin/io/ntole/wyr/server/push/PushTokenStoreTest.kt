package io.ntole.wyr.server.push

import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.push.PushPlatform
import io.ntole.wyr.core.question.QuestionStatus
import io.ntole.wyr.server.auth.SessionStore
import io.ntole.wyr.server.db.Players
import io.ntole.wyr.server.db.PushTokens
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Sessions
import io.ntole.wyr.server.db.WAITING_ON_A_ROW_LOCK
import io.ntole.wyr.server.db.appTables
import io.ntole.wyr.server.db.connectH2
import io.ntole.wyr.server.db.h2Url
import io.ntole.wyr.server.db.raceBehindFirst
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.plugins.ApiFailure
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The devices each player's pushes reach (CLAUDE.md §8a, *Push tokens*), at the server's READ COMMITTED. */
class PushTokenStoreTest {
    private val url = h2Url("wyr-push-tokens-${UUID.randomUUID()}")
    private val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)

    init {
        transaction(database) { SchemaUtils.create(*appTables) }
    }

    @Test
    fun `a token is kept under the player and session that registered it`() {
        val device = newDevice()

        register("token-1", device)

        assertEquals(listOf(Row("token-1", device, PushPlatform.ANDROID)), rows())
    }

    @Test
    fun `a token registered again moves to whoever sent it last`() {
        val first = newDevice()
        val second = newDevice()

        register("token-1", first)
        register("token-1", second, PushPlatform.WEB)

        assertEquals(listOf(Row("token-1", second, PushPlatform.WEB)), rows(), "one row, the latest owner's")
    }

    @Test
    fun `a removal takes only the caller's token`() {
        val first = newDevice()
        val second = newDevice()
        register("token-1", first)
        register("token-2", second)

        transaction(database) {
            PushTokenStore.remove("token-2", first.playerId)
            PushTokenStore.remove("token-1", first.playerId)
            PushTokenStore.remove("no-such-token", first.playerId)
        }

        assertEquals(listOf(Row("token-2", second, PushPlatform.ANDROID)), rows())
    }

    @Test
    fun `a logout takes the tokens of the session it ends and no other`() {
        val phone = newDevice()
        val tablet = newDevice(playerId = phone.playerId)
        register("phone-token", phone)
        register("tablet-token", tablet)

        transaction(database) { SessionStore.close(phone.sessionId, phone.playerId) }

        assertEquals(listOf(Row("tablet-token", tablet, PushPlatform.ANDROID)), rows())
    }

    @Test
    fun `deleting a player with their sessions leaves none of their tokens`() {
        val gone = newDevice()
        val staying = newDevice()
        register("gone-token", gone)
        register("staying-token", staying)

        transaction(database) {
            Sessions.deleteWhere { Sessions.playerId eq gone.playerId }
            Players.deleteWhere { Players.id eq gone.playerId }
        }

        assertEquals(listOf(Row("staying-token", staying, PushPlatform.ANDROID)), rows())
    }

    @Test
    fun `a session already ended registers nothing`() {
        val device = newDevice()
        transaction(database) { SessionStore.close(device.sessionId, device.playerId) }

        val refusal = assertFailsWith<ApiFailure> { register("token-1", device) }

        assertEquals(ErrorCode.UNAUTHORIZED, refusal.code)
        assertEquals(emptyList(), rows())
    }

    @Test
    fun `a player's pushes reach only their ten newest devices`() {
        val player = newDevice()
        val devices = List(PushTokenStore.MAX_TOKENS_PER_PLAYER + 2) { newDevice(playerId = player.playerId) }

        devices.forEachIndexed { index, device -> register("token-$index", device, at = index.toLong()) }
        // Registered again, the oldest is the newest.
        register("token-0", devices[0], at = 100)

        val kept = rows().map { it.token }.toSet()
        assertEquals((listOf(0) + (3 until devices.size)).map { "token-$it" }.toSet(), kept)
    }

    @Test
    fun `a question's pushes go to its author's devices and a seed's to nobody`() {
        val author = newDevice()
        val other = newDevice()
        register("author-phone", author, at = 1)
        register("author-tablet", newDevice(playerId = author.playerId), at = 2)
        register("other-phone", other)
        transaction(database) {
            question("theirs", author = author.playerId)
            question("seed", author = null)
        }

        val recipients = transaction(database) { PushTokenStore.recipientsOf("theirs") }

        assertEquals(PushTokenStore.Recipients(author.playerId, listOf("author-tablet", "author-phone")), recipients)
        assertNull(transaction(database) { PushTokenStore.recipientsOf("seed") })
        assertNull(transaction(database) { PushTokenStore.recipientsOf("no-such-question") })
    }

    @Test
    fun `a token Firebase no longer knows is forgotten whoever holds it`() {
        val device = newDevice()
        register("token-1", device)

        transaction(database) { PushTokenStore.forget("token-1") }

        assertEquals(emptyList(), rows())
    }

    /**
     * Two first registrations of one token, from two players: both find no row, both insert, and the key
     * refuses the second until Exposed's rerun finds the first's row and moves it. The later one owns it.
     */
    @Test
    fun `two first registrations of one token racing leave one row the later one's`() {
        val first = newDevice()
        val second = newDevice()

        raceBehindFirst(
            url,
            database,
            { PushTokenStore.register("token-1", first.playerId, first.sessionId, PushPlatform.ANDROID) },
            { PushTokenStore.register("token-1", second.playerId, second.sessionId, PushPlatform.IOS) },
            queued = "($WAITING_ON_A_ROW_LOCK OR UPPER(EXECUTING_STATEMENT) LIKE 'INSERT INTO PUSH_TOKENS%')",
        )

        assertEquals(listOf(Row("token-1", second, PushPlatform.IOS)), rows())
    }

    /**
     * A registration pruning its player's oldest token while another player moves that very token to
     * their device: the prune reads it as its player's, then waits on the move's row lock, and once the
     * move commits the token is the other player's, so the prune leaves it where it went.
     */
    @Test
    fun `pruning leaves a token another player moved to their device meanwhile`() {
        val pruning = newDevice()
        val devices = List(PushTokenStore.MAX_TOKENS_PER_PLAYER) { newDevice(playerId = pruning.playerId) }
        devices.forEachIndexed { index, device -> register("token-$index", device, at = index.toLong()) }
        val mover = newDevice()

        val move = { PushTokenStore.register("token-0", mover.playerId, mover.sessionId, PushPlatform.IOS, now = 100) }
        val prune = {
            PushTokenStore.register("token-new", pruning.playerId, pruning.sessionId, PushPlatform.ANDROID, now = 50)
        }
        raceBehindFirst(url, database, move, prune)

        assertEquals(Row("token-0", mover, PushPlatform.IOS), rows().single { it.token == "token-0" })
        assertEquals(
            ((1 until devices.size).map { "token-$it" } + "token-new").toSet(),
            rows().filter { it.device.playerId == pruning.playerId }.map { it.token }.toSet(),
        )
    }

    /** One device's session of a player. */
    private data class Device(
        val playerId: String,
        val sessionId: String,
    )

    private data class Row(
        val token: String,
        val device: Device,
        val platform: PushPlatform,
    )

    /** A new session of [playerId], or of a new guest. */
    private fun newDevice(playerId: String? = null): Device =
        transaction(database) {
            val player = playerId ?: PlayerStore.createGuest().id
            val session =
                SessionStore.open(
                    player,
                    refreshTokenHash = UUID.randomUUID().toString(),
                    expiresAt = Long.MAX_VALUE,
                )
            Device(player, session.id)
        }

    private fun register(
        token: String,
        device: Device,
        platform: PushPlatform = PushPlatform.ANDROID,
        at: Long = System.currentTimeMillis(),
    ) = transaction(database) { PushTokenStore.register(token, device.playerId, device.sessionId, platform, now = at) }

    private fun rows(): List<Row> =
        transaction(database) {
            PushTokens.selectAll().map { row ->
                Row(
                    row[PushTokens.token],
                    Device(row[PushTokens.playerId], row[PushTokens.sessionId]),
                    row[PushTokens.platform],
                )
            }
        }.sortedBy { it.token }

    private fun question(
        id: String,
        author: String?,
    ) = Questions.insert { row ->
        row[Questions.id] = id
        row[optionA] = "A of $id"
        row[optionB] = "B of $id"
        row[authorPlayerId] = author
        row[status] = QuestionStatus.PENDING
        row[submittedAt] = 1
    }
}
