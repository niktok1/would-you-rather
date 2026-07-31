package io.ntole.wyr.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ntole.wyr.server.config.ServerConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

object DatabaseFactory {
    /**
     * Connects, creates any missing tables, and seeds the starter questions.
     *
     * `SchemaUtils.create` only ever *adds* missing tables — it will not alter or drop an existing
     * one. That is the right amount of automation for a schema with no deployed history, and it
     * deliberately avoids `createMissingTablesAndColumns`, which Exposed has deprecated for
     * leaving the database in an unpredictable state if it fails partway.
     *
     * The moment a column has to change type, be renamed, or be dropped, this needs a real
     * migration tool (`exposed-migration-jdbc` plus Flyway). Nothing here will do it for you.
     */
    fun init(config: ServerConfig): Database {
        val dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = config.jdbcUrl
                    config.dbUser?.let { username = it }
                    config.dbPassword?.let { password = it }
                    maximumPoolSize = if (config.isEphemeralDatabase) 2 else POOL_SIZE
                    isAutoCommit = false
                    transactionIsolation = "TRANSACTION_REPEATABLE_READ"
                    validate()
                },
            )

        val database = Database.connect(dataSource)

        transaction(database) {
            SchemaUtils.create(Players, Questions, Votes)
            Seed.questionsIfEmpty()
        }

        return database
    }

    /**
     * Render's free Postgres allows few connections, and the free web service spins down when
     * idle (CLAUDE.md §8) — a small pool is the right shape here, not a large one.
     */
    private const val POOL_SIZE = 5
}
