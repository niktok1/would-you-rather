package io.ntole.wyr.server.db

import io.ntole.wyr.core.category.CategoryDto
import io.ntole.wyr.core.question.SubmitQuestionRequest
import io.ntole.wyr.server.TestDatabaseSettings
import io.ntole.wyr.server.category.CategoryStore
import io.ntole.wyr.server.moderation.ModerationStore
import io.ntole.wyr.server.question.checkedSubmission
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
     * and the rerun finds the seeds committed and writes nothing (Seed.writeMissing).
     *
     * Every seed meets the first's keys in `categories`, where it writes the categories after V6's
     * before any question.
     */
    @Test
    fun `two boots seeding at once both succeed and the seeds are written once`() {
        raceTwoBoots(Seed.SEEDS, queued = INSERTING_INTO_CATEGORIES)
    }

    /** As above for the first seeds, whose categories V6 wrote, so the second meets the first in `questions`. */
    @Test
    fun `two boots seeding the first seeds at once both succeed and they are written once`() {
        raceTwoBoots(TEST_SEEDS, queued = INSERTING_INTO_QUESTIONS)
    }

    private fun raceTwoBoots(
        seeds: List<Pair<String, Seed.Starter>>,
        queued: String,
    ) {
        val url = h2Url("wyr-seed-race-${UUID.randomUUID()}")
        TestDatabaseSettings(url, user = null, password = null).serverPool().use { pool ->
            Migrations.migrate(pool)
            val database = Database.connect(pool)
            try {
                raceBehindFirst(
                    url,
                    database,
                    { Seed.writeMissing(seeds) },
                    { Seed.writeMissing(seeds) },
                    queued = queued,
                )

                assertEquals(seededOnce(seeds), transaction(database) { seeds() })
            } finally {
                TransactionManager.closeAndUnregister(database)
            }
        }
    }

    /**
     * A seed a moderator retired stays retired through the next boot, which neither writes it again
     * nor puts it back: the seed writes only what a database lacks, and changes nothing there.
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
                assertEquals(RETIRED_AT, pool.inTransaction { retiredAt(RETIRED_SEED) }, "and still retired")
            }
    }

    /**
     * A database an earlier build seeded gets the seeds added since, and their categories, at the next
     * boot. What it holds stays as it was: a retired seed stays retired, and a category a moderator
     * added under the id of one of the later seed categories keeps the names the moderator gave it,
     * with the seeds filed under that id filed under it.
     */
    @Test
    fun `a database seeded before gets the seeds added since and keeps what it holds`() {
        TestDatabaseSettings(h2Url("wyr-seed-later-${UUID.randomUUID()}"), user = null, password = null)
            .serverPool()
            .use { pool ->
                DatabaseFactory.migrateAndSeed(pool, TEST_SEEDS).also { TransactionManager.closeAndUnregister(it) }
                val moderators = CategoryDto(Seed.SPORTS, nameSr = "Спортови", nameEn = "Sport")
                pool.inTransaction {
                    ModerationStore.retire(RETIRED_SEED, now = RETIRED_AT)
                    CategoryStore.create(moderators)
                }

                DatabaseFactory.migrateAndSeed(pool).also { TransactionManager.closeAndUnregister(it) }

                assertEquals(seededOnce(), pool.inTransaction { seeds() }, "every seed, each once")
                assertEquals(RETIRED_AT, pool.inTransaction { retiredAt(RETIRED_SEED) }, "still retired")
                val categories = pool.inTransaction { CategoryStore.all() }
                assertEquals(Seed.ALL_CATEGORIES.map { it.id }.toSet(), categories.map { it.id }.toSet())
                assertEquals(moderators, categories.single { it.id == Seed.SPORTS }, "the moderator's names")
                val filedUnderSports =
                    pool.inTransaction {
                        QuestionCategories
                            .selectAll()
                            .where { QuestionCategories.category eq Seed.SPORTS }
                            .count()
                    }
                val seedsUnderSports = Seed.SEEDS.count { (_, seed) -> Seed.SPORTS in seed.categories }
                assertEquals(seedsUnderSports.toLong(), filedUnderSports)
            }
    }

    /**
     * The store tests build their tables from the definitions, which hold no category, so the seed
     * writes V6's there too: a database so built holds the categories a migrated one does.
     */
    @Test
    fun `the seed writes the categories into a database that has none as into a migrated one`() {
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
                        Seed.writeMissing()
                        categories()
                    }
                }

        assertEquals(Seed.ALL_CATEGORIES.size, migrated.size)
        assertEquals(migrated, built)
    }

    private fun categories(): List<List<Any>> =
        Categories.selectAll().orderBy(Categories.id).map { row ->
            listOf(row[Categories.id], row[Categories.nameSr], row[Categories.nameEn], row[Categories.createdAt])
        }

    /**
     * Every seed is filed under categories the seed writes, and every category it writes has seeds, so
     * none is missing from a database and none there is empty: a seed names its categories by id, and
     * one naming an id not in the list would lose it without a word.
     */
    @Test
    fun `every seed is filed under the seed's categories and every one of them holds seeds`() {
        val ids = Seed.ALL_CATEGORIES.map { it.id }

        assertEquals(ids.size, ids.toSet().size, "no category twice")
        Seed.SEEDS.forEach { (id, seed) ->
            assertTrue(seed.category in ids && (seed.alsoIn == null || seed.alsoIn in ids), id)
            assertEquals(listOfNotNull(seed.category, seed.alsoIn).toSet(), seed.categories.toSet(), id)
        }
        assertEquals(ids.toSet(), Seed.SEEDS.flatMap { (_, seed) -> seed.categories }.toSet())
    }

    /** The server tests' seeds are filed under V6's categories alone, as the builds before wrote them. */
    @Test
    fun `the test seeds are the first ones and filed under V6's categories alone`() {
        assertEquals((1..TEST_SEEDS.size).map { "seed-$it" }, TEST_SEEDS.map { (id, _) -> id })
        assertEquals(
            Seed.CATEGORIES.map { it.id }.toSet(),
            TEST_SEEDS.flatMap { (_, seed) -> seed.categories }.toSet(),
        )
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

    /**
     * Every seed is a question a player could submit exactly as it stands (CLAUDE.md §8d,
     * *Submitting*): two options, trimmed already, one line each and no longer than an option may be,
     * that differ ignoring case. And no two seeds ask the same thing, whichever way round.
     */
    @Test
    fun `every seed keeps the rules of a submission and no two ask the same`() {
        Seed.SEEDS.forEach { (id, seed) ->
            val request = SubmitQuestionRequest(seed.optionA, seed.optionB, seed.categories)
            assertEquals(request, checkedSubmission(request), id)
        }
        val asked = Seed.SEEDS.map { (_, seed) -> setOf(seed.optionA.lowercase(), seed.optionB.lowercase()) }
        assertEquals(asked.size, asked.toSet().size, "no two seeds alike")
    }

    /**
     * The seeds are in Serbian, in Cyrillic: every letter of every option is one of the Serbian
     * Cyrillic alphabet's thirty, so neither a Latin lookalike nor a Russian letter slips in.
     */
    @Test
    fun `every seed is written in Serbian Cyrillic`() {
        val letters = SERBIAN_CYRILLIC + SERBIAN_CYRILLIC.uppercase()

        Seed.SEEDS.forEach { (id, seed) ->
            listOf(seed.optionA, seed.optionB).forEach { option ->
                assertEquals("", option.filter { it.isLetter() && it !in letters }, "$id: $option")
            }
        }
        Seed.ALL_CATEGORIES.forEach { category ->
            assertEquals("", category.nameSr.filter { it.isLetter() && it !in letters }, category.id)
        }
    }

    /** What seeding [seeds] writes, on a database of its own. */
    private fun seededOnce(seeds: List<Pair<String, Seed.Starter>> = Seed.SEEDS): Map<String, Long> =
        TestDatabaseSettings(h2Url("wyr-seed-once-${UUID.randomUUID()}"), user = null, password = null)
            .serverPool()
            .use { pool ->
                Migrations.migrate(pool)
                pool.inTransaction {
                    Seed.writeMissing(seeds)
                    seeds()
                }
            }

    private fun seeds(): Map<String, Long> =
        mapOf(
            "categories" to Categories.selectAll().count(),
            "questions" to Questions.selectAll().count(),
            "question_categories" to QuestionCategories.selectAll().count(),
        )

    private fun retiredAt(id: String): Long? =
        Questions
            .select(Questions.retiredAt)
            .where { Questions.id eq id }
            .single()[Questions.retiredAt]

    private companion object {
        const val RETIRED_SEED = "seed-1"
        const val RETIRED_AT = 5_000L
        const val SERBIAN_CYRILLIC = "абвгдђежзијклљмнњопрстћуфхцчџш"
    }
}
