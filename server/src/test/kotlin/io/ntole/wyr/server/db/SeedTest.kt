package io.ntole.wyr.server.db

import io.ntole.wyr.server.TestDatabaseSettings
import io.ntole.wyr.server.moderation.ModerationStore
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    /**
     * A seed a moderator retired stays retired through the next boot, which neither writes it again
     * nor puts it back: the seed writes only into a database with no question in it.
     */
    @Test
    fun `a retired seed stays retired through a boot and is not written again`() {
        TestDatabaseSettings(h2Url("wyr-seed-retired-${UUID.randomUUID()}"), user = null, password = null)
            .serverPool()
            .use { pool ->
                DatabaseFactory.migrateAndSeed(pool).also { TransactionManager.closeAndUnregister(it) }
                pool.inTransaction { ModerationStore.retire(RETIRED_SEED, now = RETIRED_AT) }

                DatabaseFactory.migrateAndSeed(pool).also { TransactionManager.closeAndUnregister(it) }

                assertEquals(seededOnce(), pool.inTransaction { seeds() }, "nothing written again")
                val retiredAt =
                    pool.inTransaction {
                        Questions
                            .select(Questions.retiredAt)
                            .where { Questions.id eq RETIRED_SEED }
                            .single()[Questions.retiredAt]
                    }
                assertEquals(RETIRED_AT, retiredAt, "and still retired")
            }
    }

    /**
     * The store tests build their tables from the definitions, which hold no category, so the seed
     * writes the first ones there, as V6 writes them into a migrated database, where it writes none.
     */
    @Test
    fun `the seed writes the categories V6 writes into a database that has none`() {
        val migrated =
            TestDatabaseSettings(h2Url("wyr-seed-migrated-${UUID.randomUUID()}"), user = null, password = null)
                .serverPool()
                .use { pool ->
                    DatabaseFactory.migrateAndSeed(pool).also { TransactionManager.closeAndUnregister(it) }
                    pool.inTransaction { categories() }
                }
        val built =
            TestDatabaseSettings(h2Url("wyr-seed-built-${UUID.randomUUID()}"), user = null, password = null)
                .serverPool()
                .use { pool ->
                    pool.inTransaction {
                        SchemaUtils.create(*appTables)
                        Seed.questionsIfEmpty()
                        categories()
                    }
                }

        assertEquals(Seed.CATEGORIES.size, migrated.size)
        assertEquals(migrated, built)
    }

    private fun categories(): List<List<Any>> =
        Categories.selectAll().orderBy(Categories.id).map { row ->
            listOf(row[Categories.id], row[Categories.nameSr], row[Categories.nameEn], row[Categories.createdAt])
        }

    /**
     * Every seed starts with made-up votes, so its split looks like a crowd's from the first answer,
     * and no two alike: a different total and a different split each.
     */
    @Test
    fun `every seed has made-up votes of its own`() {
        val votes = Seed.SEEDS.map { (_, seed) -> seed.votes }

        assertTrue(votes.all { (a, b) -> a > 0 && b > 0 }, "both sides of every seed")
        assertEquals(votes.size, votes.map { (a, b) -> a + b }.toSet().size, "no two totals alike")
        assertEquals(votes.size, votes.map { (a, b) -> a * 1_000 / (a + b) }.toSet().size, "no two splits alike")
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

    private companion object {
        const val RETIRED_SEED = "seed-1"
        const val RETIRED_AT = 5_000L
    }
}
