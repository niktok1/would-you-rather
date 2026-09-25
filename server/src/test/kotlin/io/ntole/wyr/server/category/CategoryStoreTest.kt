package io.ntole.wyr.server.category

import io.ntole.wyr.core.error.ErrorCode
import io.ntole.wyr.server.db.Categories
import io.ntole.wyr.server.db.Seed
import io.ntole.wyr.server.db.appTables
import io.ntole.wyr.server.db.connectH2
import io.ntole.wyr.server.db.h2Url
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
    private val database =
        connectH2(h2Url("wyr-category-store-${UUID.randomUUID()}"), Connection.TRANSACTION_READ_COMMITTED)

    init {
        transaction(database) {
            SchemaUtils.create(*appTables)
            Seed.questionsIfEmpty()
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
