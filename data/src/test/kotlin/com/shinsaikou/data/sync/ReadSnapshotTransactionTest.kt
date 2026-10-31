package com.shinsaikou.data.sync

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Phase 5 unit TG4 (R140 item 10's carried item): what the fingerprint's snapshot throws when its
 * read, its `ROLLBACK` or its `COMMIT` fails, and that it waits out a lock as the app's connections
 * do, through [readSnapshot], the code
 * `SyncStoreSnapshotImpl.read` runs once its connection is open. The connection is a fake that
 * records each statement and fails the ones a test names, because the bundled SQLite the product
 * opens does not load on the host.
 */
class ReadSnapshotTransactionTest {

    @Test
    fun `a read that succeeds commits and closes the connection`() = runTest {
        val connection = FakeConnection()

        val result = readSnapshot(connection) { "read" }

        result shouldBe "read"
        connection.stepped shouldContainExactly listOf(BUSY_TIMEOUT, BEGIN, SCHEMA_READ, COMMIT)
        connection.closed shouldBe true
    }

    @Test
    fun `a read that fails is thrown as itself after the rollback, and the connection is closed`() = runTest {
        val connection = FakeConnection()
        val failure = IllegalStateException("the read failed")

        val thrown = shouldThrow<IllegalStateException> { readSnapshot(connection) { throw failure } }

        thrown shouldBeSameInstanceAs failure
        thrown.suppressed.toList().shouldBeEmpty()
        connection.stepped shouldContainExactly listOf(BUSY_TIMEOUT, BEGIN, SCHEMA_READ, ROLLBACK)
        connection.closed shouldBe true
    }

    @Test
    fun `a rollback that fails too is suppressed under the read's failure, not thrown in its place`() = runTest {
        val rollbackFailure = IllegalStateException("the rollback failed")
        val connection = FakeConnection(failing = mapOf(ROLLBACK to rollbackFailure))
        val failure = IllegalArgumentException("the read failed")

        val thrown = shouldThrow<IllegalArgumentException> { readSnapshot(connection) { throw failure } }

        thrown shouldBeSameInstanceAs failure
        thrown.suppressed.toList() shouldContainExactly listOf(rollbackFailure)
        connection.closed shouldBe true
    }

    @Test
    fun `a cancel stays a cancel when the rollback fails under it`() = runTest {
        val rollbackFailure = IllegalStateException("the rollback failed")
        val connection = FakeConnection(failing = mapOf(ROLLBACK to rollbackFailure))
        val cancel = CancellationException("the screen left")

        val thrown = shouldThrow<CancellationException> { readSnapshot(connection) { throw cancel } }

        thrown shouldBeSameInstanceAs cancel
        thrown.suppressed.toList() shouldContainExactly listOf(rollbackFailure)
        connection.closed shouldBe true
    }

    @Test
    fun `a commit that fails is thrown as itself with no rollback after it, and the connection is closed`() = runTest {
        val commitFailure = IllegalStateException("the commit failed")
        val connection = FakeConnection(failing = mapOf(COMMIT to commitFailure))

        val thrown = shouldThrow<IllegalStateException> { readSnapshot(connection) { "read" } }

        thrown shouldBeSameInstanceAs commitFailure
        connection.stepped shouldContainExactly listOf(BUSY_TIMEOUT, BEGIN, SCHEMA_READ, COMMIT)
        connection.closed shouldBe true
    }

    /** Records every statement stepped, in order, and throws the failure [failing] names for its SQL. */
    private class FakeConnection(private val failing: Map<String, Throwable> = emptyMap()) : SQLiteConnection {
        val stepped = mutableListOf<String>()
        var closed = false

        override fun prepare(sql: String): SQLiteStatement = FakeStatement(sql)

        override fun close() {
            closed = true
        }

        private inner class FakeStatement(private val sql: String) : SQLiteStatement {
            override fun step(): Boolean {
                stepped += sql
                failing[sql]?.let { throw it }
                return false
            }

            override fun reset() = Unit
            override fun clearBindings() = Unit
            override fun close() = Unit
            override fun bindBlob(index: Int, value: ByteArray) = unused()
            override fun bindDouble(index: Int, value: Double) = unused()
            override fun bindLong(index: Int, value: Long) = unused()
            override fun bindText(index: Int, value: String) = unused()
            override fun bindNull(index: Int) = unused()
            override fun getBlob(index: Int): ByteArray = unused()
            override fun getDouble(index: Int): Double = unused()
            override fun getLong(index: Int): Long = unused()
            override fun getText(index: Int): String = unused()
            override fun isNull(index: Int): Boolean = unused()
            override fun getColumnCount(): Int = unused()
            override fun getColumnName(index: Int): String = unused()
            override fun getColumnType(index: Int): Int = unused()

            private fun unused(): Nothing = throw UnsupportedOperationException("no row is read here: $sql")
        }
    }

    private companion object {
        const val BUSY_TIMEOUT = "PRAGMA busy_timeout = 3000"
        const val BEGIN = "BEGIN DEFERRED"
        const val SCHEMA_READ = "SELECT count(*) FROM sqlite_master"
        const val ROLLBACK = "ROLLBACK"
        const val COMMIT = "COMMIT"
    }
}
