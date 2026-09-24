package io.ntole.wyr.server.db

import io.ntole.wyr.server.TestDatabaseSettings
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class SeedTest {
    /**
     * Two servers seeding one freshly migrated database, as two boots at once do once Flyway lets
     * them past the migration. The first holds its transaction open until the second's insert waits
     * on the first's keys. The second then fails on the primary key, Exposed reruns its transaction,
     * and the rerun finds the seeds committed and writes nothing (Seed.questionsIfEmpty).
     */
    @Test
    fun `two boots seeding at once both succeed and the seeds are written once`() {
        val url = h2Url("wyr-seed-race-${UUID.randomUUID()}")
        TestDatabaseSettings(url, user = null, password = null).serverPool().use { pool ->
            Migrations.migrate(pool)
            val database = Database.connect(pool)
            try {
                raceBehindFirst(
                    url,
                    database,
                    { Seed.questionsIfEmpty() },
                    { Seed.questionsIfEmpty() },
                    queued = INSERTING_INTO_QUESTIONS,
                )

                assertEquals(seededOnce(), transaction(database) { seeds() })
            } finally {
                TransactionManager.closeAndUnregister(database)
            }
        }
    }

    /** What one seed writes, on a database of its own. */
    private fun seededOnce(): Map<String, Long> =
        TestDatabaseSettings(h2Url("wyr-seed-once-${UUID.randomUUID()}"), user = null, password = null)
            .serverPool()
            .use { pool ->
                Migrations.migrate(pool)
                pool.inTransaction {
                    Seed.questionsIfEmpty()
                    seeds()
                }
            }

    private fun seeds(): Map<String, Long> =
        mapOf(
            "questions" to Questions.selectAll().count(),
            "question_categories" to QuestionCategories.selectAll().count(),
        )
}
