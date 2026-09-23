package io.ntole.wyr.server.vote

import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.server.db.Players
import io.ntole.wyr.server.db.Questions
import io.ntole.wyr.server.db.Seed
import io.ntole.wyr.server.db.Votes
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertFailsWith

class VoteStoreTest {
    @Test
    fun `a vote the database refuses for any reason but a duplicate is not reported as one`() {
        val database =
            Database.connect("jdbc:h2:mem:wyr-vote-store;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction(database) {
            SchemaUtils.create(Players, Questions, Votes)
            Seed.questionsIfEmpty()
        }

        // No such player, so the Votes foreign key refuses the row. This used to come back as
        // ALREADY_VOTED, which the client skips without a word; it is a server fault.
        assertFailsWith<ExposedSQLException> {
            transaction(database) {
                VoteStore.insertVote(playerId = "no-such-player", questionId = "seed-1", choice = OptionSide.A)
            }
        }
    }
}
