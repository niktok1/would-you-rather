package io.ntole.wyr.server.db

import org.jetbrains.exposed.v1.core.vendors.H2Dialect
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import javax.sql.DataSource

/**
 * Prints what the next migration has to hold (CLAUDE.md §8b): the statements exposed-migration finds
 * between the committed scripts and the table definitions in `Tables.kt`.
 *
 * Run it with `./gradlew :server:pendingMigration` after changing a definition. It migrates an empty
 * H2 database with every committed script, then asks exposed-migration what would make it match
 * [appTables], and does the same on the external database when `WYR_TEST_JDBC_URL` names one, which
 * it wipes first, as the test suite does: never name the production database there.
 *
 * What it prints is a draft, not the script. Write it as one `V<n>__<what_it_does>.sql` that runs on
 * both engines, the identifiers unquoted and in lower case as in V1, and check what it does to rows
 * already there: a new NOT NULL column needs a default or a backfill, and exposed-migration drops a
 * column the definitions no longer have, with its data. SchemaDriftTest then holds the script to the
 * definitions on both engines.
 */
fun main() {
    for (engine in SchemaTestEngine.all()) {
        engine.emptyDatabase("pending").serverPool().use { pool ->
            Migrations.migrate(pool)
            val version = checkNotNull(serverFlyway(pool).info().current()) { "no script ran" }.version.version
            val statements = pendingStatements(pool)

            println()
            println("-- $engine, migrated to V$version:")
            if (statements.isEmpty()) {
                println("-- nothing pending: the scripts already build what Tables.kt describes.")
            } else {
                println("-- V${version.toInt() + 1}__<what_it_does>.sql")
                statements.forEach { println("$it;") }
            }
        }
    }
}

/**
 * The statements exposed-migration would run to make the database behind [dataSource] match
 * [appTables]: missing tables, columns, indexes and constraints, columns and indexes the definitions
 * no longer have, and columns whose type, nullability or default differs.
 */
internal fun pendingStatements(dataSource: DataSource): List<String> =
    dataSource.inTransaction {
        val madeByH2 = indexesH2MadeForConstraints()
        MigrationUtils
            .statementsRequiredForDatabaseMigration(*appTables, withLogs = false)
            .filterNot { statement -> madeByH2.any { index -> statement == "DROP INDEX IF EXISTS $index" } }
    }

/**
 * The indexes H2 builds by itself to back a constraint, one for every foreign key among them.
 * exposed-migration takes each for an index the definitions no longer have, since none declares it,
 * and drafts dropping it. That is no drift, and H2 would refuse to drop one anyway. None on
 * PostgreSQL, which builds no index for a foreign key and names the one behind a key or a unique
 * constraint after the constraint, which exposed-migration recognises.
 */
private fun JdbcTransaction.indexesH2MadeForConstraints(): Set<String> {
    if (currentDialect !is H2Dialect) return emptySet()
    return exec("SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE IS_GENERATED") { rows ->
        buildSet { while (rows.next()) add(rows.getString("INDEX_NAME")) }
    }.orEmpty()
}
