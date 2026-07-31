package io.ntole.wyr.server.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Runs Exposed transactions off the request thread.
 *
 * Exposed's JDBC layer is blocking, and Ktor's request threads are not for blocking work, so
 * every query goes through here rather than being called directly from a handler.
 */
class Db(
    private val database: Database,
) {
    suspend fun <T> query(block: () -> T): T =
        withContext(Dispatchers.IO) {
            transaction(database) { block() }
        }
}
