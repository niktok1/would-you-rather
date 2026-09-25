package io.ntole.wyr.server.category

import io.ntole.wyr.core.category.CategoryDto
import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.server.db.Categories
import io.ntole.wyr.server.db.INSERTING_INTO_CATEGORIES
import io.ntole.wyr.server.db.Seed
import io.ntole.wyr.server.db.TEST_SEEDS
import io.ntole.wyr.server.db.appTables
import io.ntole.wyr.server.db.connectH2
import io.ntole.wyr.server.db.h2Url
import io.ntole.wyr.server.db.raceBehindFirst
import io.ntole.wyr.server.plugins.ApiFailure
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The categories as the server checks and orders what a request names (CLAUDE.md §8d, *Categories*). */
class CategoryStoreTest {
    private val url = h2Url("wyr-category-store-${UUID.randomUUID()}")
    private val database = connectH2(url, Connection.TRANSACTION_READ_COMMITTED)

    init {
        transaction(database) {
            SchemaUtils.create(*appTables)
            Seed.writeMissing(TEST_SEEDS)
        }
    }

    @Test
    fun `the categories are oldest first whatever their ids`() {
        // First by id of them all, and added last.
        add("ANIMALS", createdAt = Long.MAX_VALUE)

        assertEquals(
            listOf("FOOD", "LIFESTYLE", "ETHICS", "SUPERPOWERS", "ABSURD", "ANIMALS"),
            transaction(database) { CategoryStore.ids() },
        )
    }

    @Test
    fun `two added in the same millisecond come in id order`() {
        add("ZEBRAS", createdAt = ADDED_AT)
        add("ANIMALS", createdAt = ADDED_AT)

        assertEquals(listOf("ANIMALS", "ZEBRAS"), transaction(database) { CategoryStore.ids() }.takeLast(2))
    }

    @Test
    fun `what a request names is checked into the order of categories each once`() {
        val checked = transaction(database) { CategoryStore.checked(listOf("ABSURD", "FOOD", "ABSURD", "ETHICS")) }

        assertEquals(listOf("FOOD", "ETHICS", "ABSURD"), checked)
        assertEquals(emptyList(), transaction(database) { CategoryStore.checked(emptyList()) }, "none for none")
    }

    @Test
    fun `an id no category has is refused as a malformed request`() {
        for (named in listOf(listOf("RANDOM"), listOf("FOOD", "FROM_THE_FUTURE"), listOf("food"), listOf(""))) {
            val refusal =
                assertFailsWith<ApiFailure>("$named") {
                    transaction(database) { CategoryStore.checked(named) }
                }

            assertEquals(ErrorCode.VALIDATION_FAILED, refusal.code, "$named")
        }
    }

    @Test
    fun `a category added comes after every one before it`() {
        val added = CategoryDto("ANIMALS", "Животиње", "Animals")

        assertEquals(added, transaction(database) { CategoryStore.create(added, now = ADDED_AT) })

        assertEquals(added, transaction(database) { CategoryStore.all() }.last())
    }

    @Test
    fun `an id a category has already is refused and nothing changes`() {
        val before = transaction(database) { CategoryStore.all() }

        val refusal =
            assertFailsWith<ApiFailure> {
                transaction(database) { CategoryStore.create(CategoryDto("FOOD", "Јело", "Meals")) }
            }

        assertEquals(ErrorCode.CATEGORY_EXISTS, refusal.code)
        assertEquals(before, transaction(database) { CategoryStore.all() })
    }

    @Test
    fun `two creations racing for one id make exactly one`() {
        // The second inserts while the first holds its key uncommitted: only the key can refuse it, and
        // Exposed's rerun then finds the first's row. Only the refusal is caught, not the key's failure,
        // which must reach Exposed for the rerun.
        val (first, second) =
            raceBehindFirst(
                url,
                database,
                { refusalOf { CategoryStore.create(CategoryDto("ANIMALS", "Животиње", "Animals")) } },
                { refusalOf { CategoryStore.create(CategoryDto("ANIMALS", "Звери", "Beasts")) } },
                queued = INSERTING_INTO_CATEGORIES,
            )

        assertEquals(null, first, "the first is made")
        assertEquals(ErrorCode.CATEGORY_EXISTS, second)
        assertEquals(
            CategoryDto("ANIMALS", "Животиње", "Animals"),
            transaction(database) { CategoryStore.all() }.single { it.id == "ANIMALS" },
        )
    }

    @Test
    fun `a rename sets both names and keeps the id and the place`() {
        val renamed = CategoryDto("ETHICS", "Морал", "Morals")

        assertEquals(renamed, transaction(database) { CategoryStore.rename(renamed) })

        val all = transaction(database) { CategoryStore.all() }
        assertEquals(renamed, all[2], "third, as it was")
    }

    @Test
    fun `renaming an id no category has is not found`() {
        val refusal =
            assertFailsWith<ApiFailure> {
                transaction(database) { CategoryStore.rename(CategoryDto("RANDOM", "Насумично", "Random")) }
            }

        assertEquals(ErrorCode.CATEGORY_NOT_FOUND, refusal.code)
    }

    /** The code [block] is refused with, or null when it is not. */
    private fun refusalOf(block: () -> Unit): ErrorCode? =
        try {
            block()
            null
        } catch (refusal: ApiFailure) {
            refusal.code
        }

    private fun add(
        id: String,
        createdAt: Long,
    ) {
        transaction(database) {
            Categories.insert { row ->
                row[Categories.id] = id
                row[nameSr] = "Име $id"
                row[nameEn] = "Name $id"
                row[Categories.createdAt] = createdAt
            }
        }
    }

    private companion object {
        /** After the first ones, which V6 dated 2026-09-25. */
        const val ADDED_AT = 1_800_000_000_000L
    }
}
