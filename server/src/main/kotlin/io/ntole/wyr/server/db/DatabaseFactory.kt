package io.ntole.wyr.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.events.Events
import io.ktor.server.application.ApplicationStopped
import io.ntole.wyr.server.config.ServerConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import javax.sql.DataSource

object DatabaseFactory {
    /**
     * Connects, migrates the schema to the latest script, and seeds the starter questions.
     *
     * The schema is Flyway's ([Migrations]), brought up to date before anything reads a table. It is
     * never `SchemaUtils.create`, which built it until the first deploy: that can only add a missing
     * table, never change one, and it keeps no record of what a database already holds (CLAUDE.md
     * §8b).
     *
     * The seed runs once the migration has committed, in a transaction of its own, so it always finds
     * the finished schema. Several servers booting at once are safe: Flyway lets one migrate while the
     * rest wait, and the seed tolerates a racing boot by itself ([Seed.questionsIfEmpty]).
     *
     * The pool is closed when [monitor] reports the application stopped. Tests start and stop a
     * whole server per case, and against a real Postgres each abandoned pool would keep holding
     * its connections until the server's connection limit ran out.
     */
    fun init(
        config: ServerConfig,
        monitor: Events,
    ): Database {
        val dataSource = HikariDataSource(poolConfig(config))
        monitor.subscribe(ApplicationStopped) { dataSource.close() }

        return migrateAndSeed(dataSource)
    }

    /**
     * What every boot does to the database behind [dataSource], apart from [init] so a test can boot
     * several servers on one database at once.
     */
    internal fun migrateAndSeed(dataSource: DataSource): Database {
        Migrations.migrate(dataSource)

        val database = Database.connect(dataSource)
        transaction(database) { Seed.questionsIfEmpty() }

        return database
    }

    /**
     * The pool's settings, apart from [init] so a test can open a pool exactly as the server does.
     *
     * READ COMMITTED, PostgreSQL's own default, applies to every transaction: Exposed takes its
     * default isolation from the data source. At this level a write to a row another transaction
     * holds waits for it and then applies to the committed row, so an SQL increment is correct
     * without a retry and a read-then-write must be a compare-and-set (CLAUDE.md §4).
     * REPEATABLE_READ refuses that second write instead (SQLState 40001), and Exposed's 3 undelayed
     * attempts ran out under a burst on one row.
     */
    internal fun poolConfig(config: ServerConfig): HikariConfig =
        HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            config.dbUser?.let { username = it }
            config.dbPassword?.let { password = it }
            maximumPoolSize = if (config.isEphemeralDatabase) 2 else POOL_SIZE
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            validate()
        }

    /**
     * Render's free Postgres allows few connections, and the free web service spins down when
     * idle (CLAUDE.md §8) — a small pool is the right shape here, not a large one.
     */
    private const val POOL_SIZE = 5
}
