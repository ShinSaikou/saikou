package com.shinsaikou.data.sync

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.shinsaikou.domain.sync.model.SyncPushRequest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.Database
import java.io.File
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.Properties

/**
 * The fingerprint's snapshot read (phase 5 unit SC6) of the merged groups (R163): `mergedGroups()`
 * and the push read through [readSnapshot], on a second connection of its own to a database file,
 * as `SyncStoreSnapshotImpl` opens one. The bundled SQLite does not load on the host, so the
 * connection is an adapter over the JDBC driver; the statements, the binding by position and the
 * single read transaction are the product's. The snapshot's handler never opens a transaction of its
 * own, so a read that asked for one would fail here.
 */
class SyncStoreSnapshotMergedGroupsTest {

    @TempDir
    lateinit var dir: File

    /** The database file with two groups, one of them an unmerge's tombstone, and one member not arrived. */
    private fun fill(): File {
        val file = File(dir, "saikou.db")
        val writer = JdbcSqliteDriver("jdbc:sqlite:${file.path}", Properties(), Database.Schema.synchronous())
        try {
            writer.execute(null, "INSERT INTO merged_groups(_id, main_source, main_url, show_all, modified_at) VALUES (1, 7, '/a', 1, 500)", 0).value
            writer.execute(
                null,
                """
                INSERT INTO merged_members(member_source, member_url, group_id, position, check_updates)
                VALUES (7, '/a', 1, 0, 1), (7, '/b', 1, 1, 0), (9, '/not/here', 1, 2, 1)
                """.trimIndent(),
                0,
            ).value
            writer.execute(null, "INSERT INTO merged_groups(_id, main_source, main_url, modified_at) VALUES (2, 7, '/c', 600)", 0).value
            // The member rows moved the first group's clock; the line under test carries 500.
            writer.execute(null, "UPDATE merged_groups SET modified_at = 500 WHERE _id = 1", 0).value
        } finally {
            writer.close()
        }
        return file
    }

    private fun connection(file: File): SQLiteConnection =
        JdbcBackedConnection(DriverManager.getConnection("jdbc:sqlite:${file.path}"))

    @Test
    fun `the snapshot reads every group with its members, the tombstone and the member not arrived too`() = runTest {
        val file = fill()

        val groups = readSnapshot(connection(file)) { it.mergedGroups() }

        groups.map { it.mainUrl } shouldContainExactly listOf("/a", "/c")
        groups[0].let { group ->
            group.mainSource shouldBe 7
            group.showAll shouldBe true
            group.modifiedAt shouldBe 500
            group.members.sortedBy { it.position }.map { Triple(it.source, it.url, it.checkUpdates) } shouldContainExactly listOf(
                Triple(7L, "/a", true),
                Triple(7L, "/b", false),
                Triple(9L, "/not/here", true),
            )
        }
        groups[1].let { tombstone ->
            tombstone.modifiedAt shouldBe 600
            tombstone.showAll shouldBe false
            tombstone.members.shouldBeEmpty()
        }
    }

    @Test
    fun `the snapshot's push read finds groups by id and ignores one that is gone`() = runTest {
        val file = fill()

        val rows = readSnapshot(connection(file)) { it.readForPush(SyncPushRequest(mergedGroupIds = setOf(2, 999))) }

        rows.mergedGroups.keys shouldBe setOf(2L)
    }

    @Test
    fun `the snapshot reads a database with no group at all as an empty list`() = runTest {
        val file = File(dir, "empty.db")
        JdbcSqliteDriver("jdbc:sqlite:${file.path}", Properties(), Database.Schema.synchronous()).close()

        readSnapshot(connection(file)) { it.mergedGroups() }.shouldBeEmpty()
    }

    /** A [SQLiteConnection] over a JDBC connection: positions bind from 1 as in SQLite, columns read from 0. */
    private class JdbcBackedConnection(private val connection: java.sql.Connection) : SQLiteConnection {
        override fun prepare(sql: String): SQLiteStatement = JdbcBackedStatement(connection.prepareStatement(sql))

        override fun close() = connection.close()
    }

    private class JdbcBackedStatement(private val statement: PreparedStatement) : SQLiteStatement {
        private var executed = false
        private var results: ResultSet? = null

        override fun step(): Boolean {
            if (!executed) {
                executed = true
                results = if (statement.execute()) statement.resultSet else null
            }
            return results?.next() ?: false
        }

        override fun reset() {
            results?.close()
            results = null
            executed = false
        }

        override fun clearBindings() = statement.clearParameters()
        override fun close() {
            results?.close()
            statement.close()
        }

        override fun bindBlob(index: Int, value: ByteArray) = statement.setBytes(index, value)
        override fun bindDouble(index: Int, value: Double) = statement.setDouble(index, value)
        override fun bindLong(index: Int, value: Long) = statement.setLong(index, value)
        override fun bindText(index: Int, value: String) = statement.setString(index, value)
        override fun bindNull(index: Int) = statement.setNull(index, java.sql.Types.NULL)
        override fun getBlob(index: Int): ByteArray = results!!.getBytes(index + 1)
        override fun getDouble(index: Int): Double = results!!.getDouble(index + 1)
        override fun getLong(index: Int): Long = results!!.getLong(index + 1)
        override fun getText(index: Int): String = results!!.getString(index + 1)
        override fun isNull(index: Int): Boolean = results!!.getObject(index + 1) == null
        override fun getColumnCount(): Int = results!!.metaData.columnCount
        override fun getColumnName(index: Int): String = results!!.metaData.getColumnName(index + 1)
        override fun getColumnType(index: Int): Int = results!!.metaData.getColumnType(index + 1)
    }
}
