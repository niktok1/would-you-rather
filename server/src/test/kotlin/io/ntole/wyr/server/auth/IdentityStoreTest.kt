package io.ntole.wyr.server.auth

import io.ntole.wyr.server.db.Identities
import io.ntole.wyr.server.db.Players
import io.ntole.wyr.server.db.appTables
import io.ntole.wyr.server.db.connectH2
import io.ntole.wyr.server.db.h2Url
import io.ntole.wyr.server.db.raceBehindFirst
import io.ntole.wyr.server.player.PlayerStore
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Play Games players linked to players here (CLAUDE.md §8a, *Play Games sign-in*), at the server's READ COMMITTED. */
class IdentityStoreTest {
    private val url = h2Url("wyr-identities-${UUID.randomUUID()}")
    private val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)

    init {
        transaction(database) { SchemaUtils.create(*appTables) }
    }

    @Test
    fun `a Play Games player linked to nobody is linked to the caller who keeps everything`() {
        val guest = newPlayer(points = 7)

        val signedIn = signIn("g-1", callerId = guest)

        assertEquals(guest, signedIn)
        assertEquals(listOf("g-1" to guest), links())
        assertEquals(7, transaction(database) { PlayerStore.find(guest)?.totalPoints })
    }

    @Test
    fun `without a caller a new player is minted for it`() {
        val signedIn = signIn("g-1", callerId = null)

        assertEquals(listOf("g-1" to signedIn), links())
        assertEquals(0, transaction(database) { PlayerStore.find(signedIn)?.totalPoints })
    }

    @Test
    fun `a caller whose token outlived them is taken for none`() {
        val signedIn = signIn("g-1", callerId = "no-such-player")

        assertNotEquals("no-such-player", signedIn)
        assertEquals(listOf("g-1" to signedIn), links())
    }

    @Test
    fun `a Play Games player linked already signs in as its player whoever calls`() {
        val owner = newPlayer()
        signIn("g-1", callerId = owner)
        val guest = newPlayer()

        assertEquals(owner, signIn("g-1", callerId = guest), "a guest meeting it switches to it")
        assertEquals(owner, signIn("g-1", callerId = null), "on a new device")
        assertEquals(owner, signIn("g-1", callerId = owner), "again")
        assertEquals(listOf("g-1" to owner), links(), "nothing new linked")
    }

    @Test
    fun `a caller linked to another Play Games player gets a new player for this one`() {
        val owner = newPlayer()
        signIn("g-1", callerId = owner)

        val second = signIn("g-2", callerId = owner)

        assertNotEquals(owner, second)
        assertEquals(setOf("g-1" to owner, "g-2" to second), links().toSet())
    }

    @Test
    fun `a player is linked once signed in and not before`() {
        val guest = newPlayer()
        assertFalse(transaction(database) { IdentityStore.isLinked(guest) })

        signIn("g-1", callerId = guest)

        assertTrue(transaction(database) { IdentityStore.isLinked(guest) })
    }

    @Test
    fun `deleting a player deletes their links`() {
        val gone = newPlayer()
        val staying = newPlayer()
        signIn("g-gone", callerId = gone)
        signIn("g-staying", callerId = staying)

        transaction(database) { Players.deleteWhere { Players.id eq gone } }

        assertEquals(listOf("g-staying" to staying), links())
    }

    /** Two devices of one Play Games player, both new: the key refuses the second link, whose rerun signs in as the first's player. */
    @Test
    fun `two sign-ins racing to link one Play Games player sign in as one player`() {
        val (first, second) =
            raceBehindFirst(
                url,
                database,
                { IdentityStore.signIn(IdentityProvider.PLAY_GAMES, "g-1", callerId = null) },
                { IdentityStore.signIn(IdentityProvider.PLAY_GAMES, "g-1", callerId = null) },
                queued = INSERTING_INTO_IDENTITIES,
            )

        assertEquals(first, second)
        assertEquals(listOf("g-1" to first), links())
        assertEquals(
            1,
            transaction(database) { Players.selectAll().count() },
            "the refused attempt's player was rolled back",
        )
    }

    /** One guest, two Play Games players at once: the unique index refuses the second link, whose rerun mints a player. */
    @Test
    fun `two sign-ins racing to link one caller link it once`() {
        val guest = newPlayer()

        val (first, second) =
            raceBehindFirst(
                url,
                database,
                { IdentityStore.signIn(IdentityProvider.PLAY_GAMES, "g-1", callerId = guest) },
                { IdentityStore.signIn(IdentityProvider.PLAY_GAMES, "g-2", callerId = guest) },
                queued = INSERTING_INTO_IDENTITIES,
            )

        assertEquals(guest, first)
        assertNotEquals(guest, second)
        assertEquals(setOf("g-1" to guest, "g-2" to second), links().toSet())
    }

    private fun newPlayer(points: Int = 0): String =
        transaction(database) {
            PlayerStore.createGuest().id.also { id -> if (points > 0) PlayerStore.addPoints(id, points) }
        }

    private fun signIn(
        subject: String,
        callerId: String?,
    ): String = transaction(database) { IdentityStore.signIn(IdentityProvider.PLAY_GAMES, subject, callerId) }

    private fun links(): List<Pair<String, String>> =
        transaction(database) {
            Identities.selectAll().map { row -> row[Identities.subject] to row[Identities.playerId] }
        }.sortedBy { it.first }

    private companion object {
        const val INSERTING_INTO_IDENTITIES = "UPPER(EXECUTING_STATEMENT) LIKE 'INSERT INTO IDENTITIES%'"
    }
}
