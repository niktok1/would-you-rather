package io.ntole.wyr.server.db

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.flywaydb.core.api.output.MigrateResult
import javax.sql.DataSource

/**
 * The schema's history, which Flyway applies at every boot (CLAUDE.md §8b).
 *
 * Every change to the schema is a script in `server/src/main/resources/db/migration`, named
 * `V<n>__<what_it_does>.sql`, one set for H2 and PostgreSQL alike. Flyway records each script it
 * runs in `flyway_schema_history` and runs each only once, in version order. A script that has run
 * must never change: Flyway refuses to boot on a changed checksum.
 *
 * The table definitions in `Tables.kt` must describe what the scripts build. SchemaDriftTest holds
 * the two together, so a definition changed without a migration fails the build.
 */
object Migrations {
    /** Where the scripts are, on the classpath. */
    const val LOCATION: String = "classpath:db/migration"

    /**
     * The version a database built before this server had migrations is recorded at, without
     * running anything: `1 BASELINE` in its history, where an empty database that ran V1 has `1 SQL`.
     *
     * Every server before this one built its database with `SchemaUtils.create(*appTables)`, and V1 is
     * the statements `SchemaUtils.createStatements(*appTables)` generates from the same definitions,
     * as they stood until V2. So such a database already holds what V1 would build, and running V1 on
     * it would only fail on what exists. SchemaDriftTest pinned that V1 builds what those definitions
     * described, names included, on H2 and on PostgreSQL, for as long as V1 was the only script;
     * MigrationsTest pins that such a database is recorded at V1 and then takes every later script
     * with its data kept, and that an empty one runs V1.
     *
     * The baseline is taken only once, by the first boot that finds the database so: after that it
     * has a history, and [migrate] never baselines a database with one.
     */
    const val BASELINE_VERSION: String = "1"

    /**
     * The tables every server before this one built, and so what marks a database as built before
     * migrations. V1 builds exactly these, which MigrationsTest pins; a later script's tables are
     * never among them.
     */
    internal val TABLES_BEFORE_MIGRATIONS: Set<String> =
        setOf("players", "questions", "question_categories", "votes", "skips", "likes")

    /**
     * Brings the database behind [dataSource] up to the latest script, and returns what that took.
     *
     * A database built before migrations is baselined first ([BASELINE_VERSION]), and only one that
     * holds every table in [TABLES_BEFORE_MIGRATIONS] and no history. Any other database with tables
     * and no history fails the boot, in Flyway's migrate, rather than being recorded at V1 whatever it
     * holds.
     *
     * Safe for several servers booting at once on PostgreSQL, as a Render deploy does. Each that finds
     * the database built before migrations baselines it: Flyway writes the history and its marker in
     * one transaction under its advisory lock, and a second baseline finds the marker and accepts it.
     * Each script then runs under the same lock, so one boot migrates while the rest wait, up to 50
     * tries a second apart, and then find nothing left to do. The baseline is not Flyway's
     * `baselineOnMigrate`, which decides it from two reads made with no lock held, whether the history
     * exists and then whether the schema is empty: a boot that migrated an empty database between
     * another's two reads sent that one to the baseline, to fail on the history the first had written.
     * The one read here is of every table at once, so a boot that sees V1's tables sees the history
     * written before them.
     */
    fun migrate(dataSource: DataSource): MigrateResult {
        val flyway = configuration().dataSource(dataSource).load()
        if (builtBeforeMigrations(dataSource, historyTable = flyway.configuration.table)) flyway.baseline()
        return flyway.migrate()
    }

    /**
     * The one Flyway configuration, without a database. Tests start from it and change only what
     * their throwaway databases need.
     *
     * Clean, which drops everything in the schema, is refused explicitly rather than by Flyway's
     * default, so no future default can arm it against the live database, and so is
     * `baselineOnMigrate` ([migrate] says why). A script whose name Flyway cannot parse fails the boot
     * instead of being skipped with a warning, which is what Flyway does with one by default: a
     * misnamed migration would otherwise never run anywhere.
     */
    internal fun configuration(): FluentConfiguration =
        Flyway
            .configure()
            .locations(LOCATION)
            .baselineOnMigrate(false)
            .baselineVersion(BASELINE_VERSION)
            .cleanDisabled(true)
            .validateMigrationNaming(true)

    /**
     * Whether the schema Flyway migrates, the connection's own, holds every table built before
     * migrations and no [historyTable]. Names are compared ignoring case: H2 folds V1's to upper case,
     * PostgreSQL to lower, and Flyway quotes its own in lower case on both.
     */
    private fun builtBeforeMigrations(
        dataSource: DataSource,
        historyTable: String,
    ): Boolean {
        val tables =
            dataSource.connection.use { connection ->
                connection.metaData.getTables(connection.catalog, connection.schema, "%", null).use { rows ->
                    buildSet { while (rows.next()) add(rows.getString("TABLE_NAME").lowercase()) }
                }
            }
        return historyTable.lowercase() !in tables && tables.containsAll(TABLES_BEFORE_MIGRATIONS)
    }
}
