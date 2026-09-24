package io.ntole.wyr.server.db

import org.jetbrains.exposed.v1.core.vendors.H2Dialect
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import javax.sql.DataSource

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
