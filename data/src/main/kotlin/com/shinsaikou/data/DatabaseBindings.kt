package com.shinsaikou.data

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.cash.sqldelight.db.SqlDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConnectionFactory
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import com.eygraber.sqldelight.androidx.driver.FileProvider
import com.eygraber.sqldelight.androidx.driver.SqliteJournalMode
import com.eygraber.sqldelight.androidx.driver.SqliteSync
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter

@BindingContainer
@ContributesTo(AppScope::class)
object DatabaseBindings {

    const val DATABASE_NAME = "saikou.db"

    /**
     * Upstream memoises its driver behind a [java.lang.ref.WeakReference]; `@SingleIn(AppScope)` holds
     * the one instance for the process lifetime instead.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideSqlDriver(context: Context): SqlDriver = AndroidxSqliteDriver(
        connectionFactory = BusyTimeoutConnectionFactory(BundledSQLiteDriver()),
        // FileProvider is an extension on the companion, shadowed by the nested class of the
        // same name unless it is called through Companion explicitly.
        databaseType = AndroidxSqliteDatabaseType.Companion.FileProvider(context, DATABASE_NAME),
        schema = Database.Schema,
        configuration = AndroidxSqliteConfiguration(
            isForeignKeyConstraintsEnabled = true,
            journalMode = SqliteJournalMode.WAL,
            sync = SqliteSync.Normal,
        ),
    )

    @Provides
    @SingleIn(AppScope::class)
    fun provideDatabase(driver: SqlDriver): Database = appDatabase(driver)

    @Provides
    @SingleIn(AppScope::class)
    fun provideDatabaseHandler(db: Database): DatabaseHandler = AndroidDatabaseHandler(db)
}

/**
 * The library's default factory, plus upstream's busy timeout on every connection it opens. SQLite's
 * own default is none: a connection that meets a lock (a checkpoint, the journal mode set at open,
 * the sync snapshot's connection) fails at once with "database is locked" instead of waiting for it.
 */
internal class BusyTimeoutConnectionFactory(override val driver: SQLiteDriver) : AndroidxSqliteConnectionFactory {
    override fun createConnection(name: String): SQLiteConnection = driver.open(name).waitWhenBusy()
}

/** Sets [BUSY_TIMEOUT_PRAGMA] on this connection, closing it if that fails, and returns it. */
internal fun SQLiteConnection.waitWhenBusy(): SQLiteConnection = apply {
    try {
        execSQL(BUSY_TIMEOUT_PRAGMA)
    } catch (e: Throwable) {
        close()
        throw e
    }
}

/** A connection waits up to three seconds for a lock before it fails, upstream's value. */
internal const val BUSY_TIMEOUT_PRAGMA = "PRAGMA busy_timeout = 3000"

/**
 * [Database] over [driver] with the app's column adapters: the app's one database, and the read
 * snapshot's ([com.shinsaikou.data.sync.SyncStoreSnapshotImpl]), which must decode rows the same way.
 */
internal fun appDatabase(driver: SqlDriver): Database = Database(
    driver = driver,
    chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
    historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
    mangasAdapter = Mangas.Adapter(
        genreAdapter = StringListColumnAdapter,
        update_strategyAdapter = UpdateStrategyColumnAdapter,
        memoAdapter = MemoColumnAdapter,
        genre_overrideAdapter = StringListColumnAdapter,
    ),
)
