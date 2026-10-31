package com.shinsaikou.data.sync

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.execSQL
import app.cash.sqldelight.ExecutableQuery
import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.shinsaikou.data.BUSY_TIMEOUT_PRAGMA
import com.shinsaikou.data.DatabaseBindings
import com.shinsaikou.data.DatabaseHandler
import com.shinsaikou.data.appDatabase
import com.shinsaikou.domain.sync.repository.SyncStore
import com.shinsaikou.domain.sync.repository.SyncStoreSnapshot
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import tachiyomi.data.Database

/**
 * The library fingerprint's read (R101 item 3) without the write lock (phase 5 unit SC6).
 *
 * The app's driver cannot give it: every transaction it opens takes its one writer connection, held
 * from `BEGIN IMMEDIATE` to the commit, and every write in the app goes through that connection, so
 * a fingerprint read in [com.shinsaikou.domain.database.TransactionRunner] kept each write waiting for
 * the whole digest (10.6 s at 10,000 entries on the 1.5 GB phone, R140 item 1). Its reader
 * connections are taken one statement at a time, so no two reads share a state there.
 *
 * So each [read] opens a connection of its own to the same file, read-only, through the bundled
 * SQLite the app's driver uses ([BundledSQLiteDriver], the library's own SQLite compiled into the
 * APK, the same on every API level; the platform's SQLite and its `SQLiteDatabase` are never
 * involved). On it, `BEGIN DEFERRED` and then one read of the schema table start a read
 * transaction: under WAL that fixes the snapshot every later statement on the connection reads,
 * however many writers commit meanwhile, and takes no write lock. The connection reads the rows
 * through the generated queries and [SyncStoreImpl], as the fingerprint did, and the transaction
 * ends with the block; the connection is closed.
 *
 * What it costs: one more connection for the length of the read (its page cache, SQLite's default
 * of about 2 MB), and the WAL cannot be checkpointed past the snapshot until it ends, so writes made
 * meanwhile stay in the log until the next checkpoint after it. Nothing about how the app's own
 * driver opens or uses the database changes.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SyncStoreSnapshotImpl(
    private val context: Context,
) : SyncStoreSnapshot {

    override suspend fun <T> read(block: suspend (SyncStore) -> T): T = withContext(Dispatchers.IO) {
        val path = context.getDatabasePath(DatabaseBindings.DATABASE_NAME).absolutePath
        readSnapshot(BundledSQLiteDriver().open(path, SQLITE_OPEN_READONLY), block)
    }
}

/**
 * [block] over [connection] in one read transaction, the connection closed afterwards whatever
 * happened: [SyncStoreSnapshotImpl.read] once its connection is open, apart so a JVM test can hand
 * it a connection of its own (the bundled SQLite does not load on the host).
 */
internal suspend fun <T> readSnapshot(connection: SQLiteConnection, block: suspend (SyncStore) -> T): T =
    ReadSnapshotDriver(connection).use { driver ->
        // As the app's own connections do (DatabaseBindings): wait out a checkpoint's lock.
        connection.execSQL(BUSY_TIMEOUT_PRAGMA)
        driver.inReadTransaction {
            val handler = SnapshotHandler(appDatabase(driver))
            block(SyncStoreImpl(handler, driver))
        }
    }

/**
 * A [SqlDriver] over one read-only [connection], for [SyncStoreSnapshotImpl]: queries only, run in
 * the calling coroutine, one at a time. A statement with an identifier is prepared once and kept
 * until [close], as the app's driver caches them.
 */
internal class ReadSnapshotDriver(private val connection: SQLiteConnection) : SqlDriver {

    private val statements = HashMap<Int, SnapshotStatement>()

    /**
     * [block] in one read transaction: `BEGIN DEFERRED` takes no lock, and the schema read after it
     * starts the read, so every statement until the end sees the database as it stood then.
     *
     * What fails is what the caller sees (phase 5 unit TG4). A throw from the read, a cancel
     * included, is rethrown as itself after the `ROLLBACK`, and a `ROLLBACK` that fails too is added
     * to it as suppressed rather than thrown in its place: the fingerprint's `Failed` then names the
     * read's cause, and a cancel stays a `CancellationException`. A `COMMIT` that fails is thrown as
     * itself with no `ROLLBACK` after it; the caller closes the connection (as [readSnapshot] does),
     * which ends the transaction.
     */
    suspend fun <T> inReadTransaction(block: suspend () -> T): T {
        connection.execSQL("BEGIN DEFERRED")
        val result = try {
            connection.prepare("SELECT count(*) FROM sqlite_master").use { it.step() }
            block()
        } catch (e: Throwable) {
            try {
                connection.execSQL("ROLLBACK")
            } catch (rollback: Throwable) {
                e.addSuppressed(rollback)
            }
            throw e
        }
        connection.execSQL("COMMIT")
        return result
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = QueryResult.AsyncValue {
        val statement = identifier?.let { statements.getOrPut(it) { SnapshotStatement(connection.prepare(sql)) } }
            ?: SnapshotStatement(connection.prepare(sql))
        try {
            binders?.invoke(statement)
            mapper(statement).await()
        } finally {
            if (identifier == null) statement.close() else statement.reset()
        }
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> = throw UnsupportedOperationException("a read snapshot writes nothing: $sql")

    override fun newTransaction(): QueryResult<Transacter.Transaction> =
        throw UnsupportedOperationException("a read snapshot is already one transaction")

    override fun currentTransaction(): Transacter.Transaction? = null

    // A snapshot never changes, so there is nothing to listen for.
    override fun addListener(vararg queryKeys: String, listener: Query.Listener) = Unit

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = Unit

    override fun notifyListeners(vararg queryKeys: String) = Unit

    override fun close() {
        statements.values.forEach { it.close() }
        statements.clear()
        connection.close()
    }
}

/**
 * One prepared statement of [ReadSnapshotDriver], bound and read exactly as the app's driver binds
 * and reads (`AndroidxQuery` and `AndroidxCursor` in the pinned driver, which are internal to it):
 * SQLDelight counts parameters from 0 and SQLite from 1, a null binds NULL, a boolean is 1 or 0,
 * and a column reads null when SQLite holds NULL there.
 */
private class SnapshotStatement(private val statement: SQLiteStatement) : SqlPreparedStatement, SqlCursor {

    override fun bindBytes(index: Int, bytes: ByteArray?) =
        if (bytes == null) statement.bindNull(index + 1) else statement.bindBlob(index + 1, bytes)

    override fun bindLong(index: Int, long: Long?) =
        if (long == null) statement.bindNull(index + 1) else statement.bindLong(index + 1, long)

    override fun bindDouble(index: Int, double: Double?) =
        if (double == null) statement.bindNull(index + 1) else statement.bindDouble(index + 1, double)

    override fun bindString(index: Int, string: String?) =
        if (string == null) statement.bindNull(index + 1) else statement.bindText(index + 1, string)

    override fun bindBoolean(index: Int, boolean: Boolean?) =
        if (boolean == null) statement.bindNull(index + 1) else statement.bindLong(index + 1, if (boolean) 1L else 0L)

    override fun next(): QueryResult<Boolean> = QueryResult.Value(statement.step())

    override fun getString(index: Int): String? = if (statement.isNull(index)) null else statement.getText(index)

    override fun getLong(index: Int): Long? = if (statement.isNull(index)) null else statement.getLong(index)

    override fun getBytes(index: Int): ByteArray? = if (statement.isNull(index)) null else statement.getBlob(index)

    override fun getDouble(index: Int): Double? = if (statement.isNull(index)) null else statement.getDouble(index)

    override fun getBoolean(index: Int): Boolean? = if (statement.isNull(index)) null else statement.getLong(index) == 1L

    fun reset() = statement.reset()

    fun close() = statement.close()
}

/**
 * [DatabaseHandler] over a snapshot's [Database]: every block runs in the calling coroutine, and
 * `inTransaction` changes nothing, because the whole snapshot is one transaction already. Each call
 * first checks that the caller is still active, as the app's handler does by switching to its
 * dispatcher, so a fingerprint cancelled mid read (the screen left, a sync started) stops at its
 * next query rather than at the end of the library.
 */
private class SnapshotHandler(private val db: Database) : DatabaseHandler {

    override suspend fun <T> await(inTransaction: Boolean, block: suspend Database.() -> T): T = active { db.block() }

    override suspend fun <T : Any> awaitList(
        inTransaction: Boolean,
        block: suspend Database.() -> ExecutableQuery<T>,
    ): List<T> = active { db.block().awaitAsList() }

    override suspend fun <T : Any> awaitOne(
        inTransaction: Boolean,
        block: suspend Database.() -> ExecutableQuery<T>,
    ): T = active { db.block().awaitAsOne() }

    override suspend fun <T : Any> awaitOneOrNull(
        inTransaction: Boolean,
        block: suspend Database.() -> ExecutableQuery<T>,
    ): T? = active { db.block().awaitAsOneOrNull() }

    private suspend inline fun <T> active(block: () -> T): T {
        currentCoroutineContext().ensureActive()
        return block()
    }

    override fun <T : Any> subscribeToList(block: Database.() -> Query<T>): Flow<List<T>> = unsupported()

    override fun <T : Any> subscribeToOne(block: Database.() -> Query<T>): Flow<T> = unsupported()

    override fun <T : Any> subscribeToOneOrNull(block: Database.() -> Query<T>): Flow<T?> = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("a read snapshot is read once, not watched")
}
