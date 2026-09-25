package io.ntole.wyr.server.auth

import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.server.db.Players
import io.ntole.wyr.server.db.WAITING_ON_A_ROW_LOCK
import io.ntole.wyr.server.db.appTables
import io.ntole.wyr.server.db.connectH2
import io.ntole.wyr.server.db.h2Url
import io.ntole.wyr.server.db.raceBehindFirst
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

/**
 * Accounts (CLAUDE.md §8b, *Accounts*), driven through the store at READ COMMITTED by hand, as the
 * other store tests are, so two registrations racing can be staged.
 */
class AccountStoreTest {
    private val url = h2Url("wyr-account-store-${UUID.randomUUID()}")
    private val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)

    init {
        transaction(database) { SchemaUtils.create(*appTables) }
    }

    @Test
    fun `a guest registers under the name and hash given, and nothing else of it changes`() {
        val player = newPlayer()
        transaction(database) { PlayerStore.addPoints(player, points = 3) }

        transaction(database) { AccountStore.register(player, "bob", passwordHash = "hash-of-bob") }

        assertEquals(Account("bob", "hash-of-bob"), accountOf(player))
        assertEquals(3, transaction(database) { PlayerStore.find(player)?.totalPoints }, "the guest's points stay")
    }

    @Test
    fun `a player registers once, under any name`() {
        val player = newPlayer()
        transaction(database) { AccountStore.register(player, "bob", passwordHash = "first") }

        listOf("bob", "carol").forEach { username ->
            assertEquals(ErrorCode.ALREADY_REGISTERED, refusalOf { AccountStore.register(player, username, "second") })
        }
        assertEquals(Account("bob", "first"), accountOf(player))
    }

    @Test
    fun `a name another player has is taken, and the second player stays a guest`() {
        val (first, second) = newPlayer() to newPlayer()
        transaction(database) { AccountStore.register(first, "bob", passwordHash = "first") }

        assertEquals(ErrorCode.USERNAME_TAKEN, refusalOf { AccountStore.register(second, "bob", "second") })
        assertEquals(Account(null, null), accountOf(second))
    }

    @Test
    fun `an unknown player cannot register`() {
        assertEquals(ErrorCode.UNAUTHORIZED, refusalOf { AccountStore.register("no-such-player", "bob", "hash") })
    }

    /**
     * Two players registering one name at once both find it free. The second's write waits on the
     * first's uncommitted name, then fails on the unique constraint once the first commits, and
     * Exposed's rerun finds the name taken. Without the read before the write, the rerun would fail on
     * the constraint again, and the request with it.
     */
    @Test
    fun `of two players racing for one name exactly one gets it and the other is told it is taken`() {
        val (first, second) = newPlayer() to newPlayer()

        val (firstOutcome, secondOutcome) =
            raceBehindFirst(
                url,
                database,
                { outcomeOf { AccountStore.register(first, "bob", passwordHash = "first") } },
                { outcomeOf { AccountStore.register(second, "bob", passwordHash = "second") } },
                queued = NAMING_A_PLAYER,
            )

        assertEquals(null, firstOutcome)
        assertEquals(ErrorCode.USERNAME_TAKEN, secondOutcome)
        assertEquals(Account("bob", "first"), accountOf(first))
        assertEquals(Account(null, null), accountOf(second))
    }

    /**
     * One player registering twice at once: the second waits on the first's row lock, then finds a
     * name set, so the compare-and-set names nobody twice.
     */
    @Test
    fun `of two registrations of one player racing exactly one names them`() {
        val player = newPlayer()

        val (first, second) =
            raceBehindFirst(
                url,
                database,
                { outcomeOf { AccountStore.register(player, "bob", passwordHash = "first") } },
                { outcomeOf { AccountStore.register(player, "carol", passwordHash = "second") } },
                queued = WAITING_ON_A_ROW_LOCK,
            )

        assertEquals(null, first)
        assertEquals(ErrorCode.ALREADY_REGISTERED, second)
        assertEquals(Account("bob", "first"), accountOf(player))
    }

    private fun newPlayer(): String = transaction(database) { PlayerStore.createGuest().id }

    /** A player's username and password hash, both null for a guest. */
    private data class Account(
        val username: String?,
        val passwordHash: String?,
    )

    private fun accountOf(playerId: String): Account =
        transaction(database) {
            Players
                .select(Players.username, Players.passwordHash)
                .where { Players.id eq playerId }
                .single()
                .let { row -> Account(row[Players.username], row[Players.passwordHash]) }
        }

    /** The code [block] is refused with, in a transaction of its own. */
    private fun refusalOf(block: () -> Unit): ErrorCode =
        assertFailsWith<ApiFailure> { transaction(database) { block() } }.code

    /** Null when [block] goes through, or the code it is refused with. For a transaction already open. */
    private fun outcomeOf(block: () -> Unit): ErrorCode? =
        try {
            block()
            null
        } catch (refused: ApiFailure) {
            refused.code
        }

    private companion object {
        /**
         * A session inside an update of `players`. H2 names no blocker for one waiting on another's
         * uncommitted unique value, as for an insert of a key another holds ([WAITING_ON_A_ROW_LOCK]
         * does not see it), so it shows only by what it executes.
         */
        const val NAMING_A_PLAYER = "UPPER(EXECUTING_STATEMENT) LIKE 'UPDATE PLAYERS%'"
    }
}
