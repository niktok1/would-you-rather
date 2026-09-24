package io.ntole.wyr.server.db

import java.sql.DatabaseMetaData
import java.sql.ResultSet
import javax.sql.DataSource

/**
 * Everything about a schema that a later migration could name or rely on, table by table: each
 * column's type, size, nullability and default, and the primary key, foreign keys and indexes, each
 * with its name. Read through JDBC's own metadata, so one reading serves H2 and PostgreSQL.
 *
 * Names are the point. A migration that drops or rebuilds an index or a constraint names it, so the
 * live database must hold every one under the name the scripts give it. exposed-migration does not
 * look at names: it takes an index that differs only in name for the same index.
 *
 * Flyway's history table is left out, since only the migrated database has one. Columns are compared
 * as a set, since a column a migration adds lands last whatever its place in the table definition.
 */
internal fun schemaSnapshot(dataSource: DataSource): Map<String, List<String>> =
    dataSource.connection.use { connection ->
        val metadata = connection.metaData
        val catalog = connection.catalog
        val schema = connection.schema
        metadata
            .getTables(catalog, schema, "%", null)
            .rows { if (getString("TABLE_TYPE") in TABLE_TYPES) getString("TABLE_NAME") else null }
            .filterNot { it.equals(HISTORY_TABLE, ignoreCase = true) }
            .sorted()
            .associateWith { table -> metadata.describe(catalog, schema, table) }
    }

private fun DatabaseMetaData.describe(
    catalog: String?,
    schema: String?,
    table: String,
): List<String> {
    val columns =
        getColumns(catalog, schema, table, "%").rows {
            val nullability = if (getString("IS_NULLABLE") == "YES") "NULL" else "NOT NULL"
            "column ${getString("COLUMN_NAME")} ${getString("TYPE_NAME")}(${getInt("COLUMN_SIZE")}) $nullability " +
                "default ${getString("COLUMN_DEF")}"
        }

    val primaryKey =
        getPrimaryKeys(catalog, schema, table)
            .rows { Triple(getString("PK_NAME"), getInt("KEY_SEQ"), getString("COLUMN_NAME")) }
            .sortedBy { it.second }
            .groupBy({ it.first }, { it.third })
            .map { (name, keyColumns) -> "primary key ${generated(name)} $keyColumns" }

    val foreignKeys =
        getImportedKeys(catalog, schema, table)
            .rows {
                val column = getString("FKCOLUMN_NAME")
                val target = "${getString("PKTABLE_NAME")}.${getString("PKCOLUMN_NAME")}"
                val rules = "on update ${getInt("UPDATE_RULE")} on delete ${getInt("DELETE_RULE")}"
                getString("FK_NAME") to "$column -> $target $rules"
            }.groupBy({ it.first }, { it.second })
            .map { (name, parts) -> "foreign key ${generated(name)} $parts" }

    val indexes =
        getIndexInfo(catalog, schema, table, false, true)
            .rows {
                val name = getString("INDEX_NAME") ?: return@rows null
                val kind = if (getBoolean("NON_UNIQUE")) "index" else "unique index"
                Triple("$kind ${generated(name)}", getInt("ORDINAL_POSITION"), getString("COLUMN_NAME"))
            }.sortedBy { it.second }
            .groupBy({ it.first }, { it.third })
            .map { (index, indexColumns) -> "$index $indexColumns" }

    return (columns + primaryKey + foreignKeys + indexes).sorted()
}

/**
 * [name] with the part H2 makes up for an object nobody named taken out, since it counts the objects
 * in the database, and the migrated one also holds the history table. PostgreSQL derives such a name
 * from the table (`players_pkey`), so none of its names matches and every one is compared whole.
 */
private fun generated(name: String): String = H2_GENERATED_NAME.replace(name) { "${it.groupValues[1]}<n>" }

private val H2_GENERATED_NAME = Regex("^(CONSTRAINT_|PRIMARY_KEY_|.+_INDEX_)[0-9A-F]+$")

private fun <T : Any> ResultSet.rows(read: ResultSet.() -> T?): List<T> =
    use {
        buildList {
            while (next()) read()?.let(::add)
        }
    }

/** A plain table: PostgreSQL's name for one, and H2's. */
private val TABLE_TYPES = setOf("TABLE", "BASE TABLE")

private const val HISTORY_TABLE = "flyway_schema_history"
