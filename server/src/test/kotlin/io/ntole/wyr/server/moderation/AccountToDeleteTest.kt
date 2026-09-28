package io.ntole.wyr.server.moderation

import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.core.player.DeleteAccountRequest
import io.ntole.wyr.server.auth.AccountStore
import io.ntole.wyr.server.db.Players
import io.ntole.wyr.server.db.Seed
import io.ntole.wyr.server.db.TEST_SEEDS
import io.ntole.wyr.server.db.appTables
import io.ntole.wyr.server.db.connectH2
import io.ntole.wyr.server.db.h2Url
import io.ntole.wyr.server.db.raceBehindFirst
import io.ntole.wyr.server.player.AccountDeletion
import io.ntole.wyr.server.player.PlayerStore
import io.ntole.wyr.server.plugins.ApiFailure
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * A moderator naming the account to delete (CLAUDE.md §8a, *Deleting an account*), by username or by
 * id, through the stores at READ COMMITTED, so two deletions of one player can be raced.
 */
class AccountToDeleteTest {
    private val url = h2Url("wyr-account-to-delete-${UUID.randomUUID()}")
    private val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)

    init {
        transaction(database) {
            SchemaUtils.create(*appTables)
            Seed.writeMissing(TEST_SEEDS)
        }
    }

    @Test
    fun `a username is trimmed and lower-cased as a login compares it`() {
        val player = registered("leaving_1")

        val named = checkedAccountDeletion(DeleteAccountRequest(username = "  Leaving_1 "))

        assertEquals(player, transaction(database) { named.lockedPlayerId() })
    }

    @Test
    fun `an account id names its player whether or not they have a username`() {
        val guest = transaction(database) { PlayerStore.createGuest().id }

        val named = checkedAccountDeletion(DeleteAccountRequest(accountId = " $guest "))

        assertEquals(guest, transaction(database) { named.lockedPlayerId() })
    }

    @Test
    fun `a name or an id no player has is PLAYER_NOT_FOUND`() {
        registered("somebody")
        listOf(
            DeleteAccountRequest(username = "nobody_here"),
            // Refused by the username rules, so no account can have it: nothing is asked of the database.
            DeleteAccountRequest(username = "no such name"),
            DeleteAccountRequest(accountId = "no-such-player"),
        ).forEach { request ->
            val refused =
                assertFailsWith<ApiFailure> {
                    transaction(
                        database,
                    ) { checkedAccountDeletion(request).lockedPlayerId() }
                }
            assertEquals(ErrorCode.PLAYER_NOT_FOUND, refused.code, "$request")
        }
    }

    @Test
    fun `neither or both or a blank one is a malformed request`() {
        listOf(
            DeleteAccountRequest(),
            DeleteAccountRequest(username = "somebody", accountId = "an-id"),
            DeleteAccountRequest(username = "  "),
            DeleteAccountRequest(accountId = " "),
            DeleteAccountRequest(accountId = "an\u0000id"),
        ).forEach { request ->
            val refused = assertFailsWith<ApiFailure> { checkedAccountDeletion(request) }
            assertEquals(ErrorCode.VALIDATION_FAILED, refused.code, "$request")
        }
    }

    /**
     * Of two deletions of one player racing, one by username and one by id, the second waits on the
     * first's lock of the player's row, then finds the row gone: 404, and nothing deleted twice.
     */
    @Test
    fun `a deletion behind another of the same player finds nobody`() {
        val player = registered("twice_asked")
        val byName = checkedAccountDeletion(DeleteAccountRequest(username = "twice_asked"))
        val byId = checkedAccountDeletion(DeleteAccountRequest(accountId = player))

        val (first, second) =
            raceBehindFirst<Any?>(
                url,
                database,
                { byName.lockedPlayerId().also(AccountDeletion::delete) },
                { runCatching { byId.lockedPlayerId().also(AccountDeletion::delete) }.exceptionOrNull() },
            )

        assertEquals(player, first)
        assertEquals(ErrorCode.PLAYER_NOT_FOUND, assertIs<ApiFailure>(second).code)
        val left = transaction(database) { Players.select(Players.id).where { Players.id eq player }.count() }
        assertEquals(0, left)
    }

    private fun registered(username: String): String =
        transaction(database) {
            PlayerStore.createGuest().id.also { id -> AccountStore.register(id, username, passwordHash = "unused") }
        }
}
