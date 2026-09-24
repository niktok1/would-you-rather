package io.ntole.wyr.server.db

import org.flywaydb.core.internal.jdbc.TableLockingExecutionTemplate
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import javax.sql.DataSource

/**
 * [delegate], with [between] run once, right after the [at]-th lookup of the schema's tables made
 * through it outside Flyway's lock has returned. Every lookup is counted in [lookups], so a run with
 * [at] 0, which never runs [between], says how many points a boot has.
 *
 * A lookup is a `DatabaseMetaData.getTables` call, which is how a boot reads the schema on H2: both
 * whether Flyway's history exists and what [Migrations.migrate] reads before it. Nothing is counted
 * inside Flyway's lock (a [TableLockingExecutionTemplate] on H2): a boot there holds a lock that
 * another would wait on, so running that other one there instead would only deadlock the test.
 */
internal class InterleavingDataSource(
    private val delegate: DataSource,
    private val at: Int,
    private val between: () -> Unit,
) : DataSource by delegate {
    var lookups: Int = 0
        private set

    /** Whether [between] has run. */
    val interleaved: Boolean get() = at in 1..lookups

    override fun getConnection(): Connection = InterleavingConnection(delegate.connection)

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = InterleavingConnection(delegate.getConnection(username, password))

    private inner class InterleavingConnection(
        private val connection: Connection,
    ) : Connection by connection {
        override fun getMetaData(): DatabaseMetaData = InterleavingMetaData(connection.metaData)
    }

    private inner class InterleavingMetaData(
        private val metaData: DatabaseMetaData,
    ) : DatabaseMetaData by metaData {
        override fun getTables(
            catalog: String?,
            schemaPattern: String?,
            tableNamePattern: String?,
            types: Array<out String>?,
        ): ResultSet =
            metaData.getTables(catalog, schemaPattern, tableNamePattern, types).also {
                if (!insideFlywaysLock()) {
                    lookups++
                    if (lookups == at) between()
                }
            }
    }

    private fun insideFlywaysLock(): Boolean =
        Thread.currentThread().stackTrace.any { it.className == TableLockingExecutionTemplate::class.java.name }
}
