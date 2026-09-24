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
 * must never change: Flyway refuses to boot on a checksum that no longer matches.
 *
 * The table definitions in `Tables.kt` must describe what the scripts build. SchemaDriftTest holds
 * the two together, so a definition changed without a migration fails the build.
 */
object Migrations {
    /** Where the scripts are, on the classpath. */
    const val LOCATION: String = "classpath:db/migration"

    /**
     * The version a database built before this server had migrations is recorded at, without
     * running anything.
     *
     * The live database is one: `SchemaUtils.create(*appTables)` built it, and V1 is the statements
     * `SchemaUtils.createStatements(*appTables)` generates from the same definitions, which have not
     * changed since. So it already holds exactly what V1 would build, and running V1 on it would only
     * fail on tables that exist. SchemaDriftTest pins that V1 builds what those definitions describe,
     * names included, on H2 and on PostgreSQL; MigrationsTest pins that such a database is recorded
     * at V1 with its data untouched, and that an empty one runs V1.
     *
     * Flyway baselines only a database that has tables and no history table, and only once: after
     * that it has a history, and a baseline never happens again. An empty database has nothing to
     * baseline and runs every script from V1.
     */
    const val BASELINE_VERSION: String = "1"

    /**
     * Brings the database behind [dataSource] up to the latest script, and returns what that took.
     *
     * Safe for several servers booting at once on PostgreSQL, as a Render deploy does: Flyway holds an
     * advisory lock over each step that writes, creating the history, the baseline, and each script,
     * so one migrates while the rest wait, up to 50 tries a second apart, and then find nothing left
     * to do. Not on H2, where two migrations of one database at once fail inside H2; but the server's
     * H2 database is in memory and belongs to the one process that opened it, so no two servers ever
     * share one.
     */
    fun migrate(dataSource: DataSource): MigrateResult = configuration().dataSource(dataSource).load().migrate()

    /**
     * The one Flyway configuration, without a database. Tests start from it and change only what
     * their throwaway databases need.
     *
     * Clean, which drops everything in the schema, is refused explicitly rather than by Flyway's
     * default, so no future default can arm it against the live database. A script whose name Flyway
     * cannot parse fails the boot instead of being skipped with a warning, which is what Flyway does
     * with one by default: a misnamed migration would otherwise never run anywhere.
     */
    internal fun configuration(): FluentConfiguration =
        Flyway
            .configure()
            .locations(LOCATION)
            .baselineOnMigrate(true)
            .baselineVersion(BASELINE_VERSION)
            .cleanDisabled(true)
            .validateMigrationNaming(true)
}
