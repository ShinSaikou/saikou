package com.shinsaikou.sync.engine

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.shinsaikou.data.AndroidDatabaseHandler
import com.shinsaikou.data.DatabaseHandler
import com.shinsaikou.data.merged.MergedGroupRepositoryImpl
import com.shinsaikou.data.source.SourceAliasRepositoryImpl
import com.shinsaikou.data.sync.SyncChangeLogRepositoryImpl
import com.shinsaikou.data.sync.SyncStoreImpl
import com.shinsaikou.domain.category.CategorySyncKey
import com.shinsaikou.domain.download.AutoDownload
import com.shinsaikou.domain.merged.interactor.ReconcileMergedRead
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.SyncRestorePreferences
import com.shinsaikou.domain.sync.repository.SyncStore
import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.MapPreferenceStore
import com.shinsaikou.sync.MergeEngine
import com.shinsaikou.sync.SyncBackend
import com.shinsaikou.sync.SyncEngine
import com.shinsaikou.sync.SyncPreferences
import com.shinsaikou.sync.SyncRunResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import java.util.Properties

/**
 * One device of a sync test: schema version 2 on the JVM SQLite driver behind the real
 * [DatabaseHandler], `:data`'s [SyncStoreImpl], change log and alias repositories, and a
 * [SyncEngine] over them, sharing [backend] with the other devices of the test.
 *
 * Local edits are plain SQL, so every trigger fires as it does for the app's own writes. The url
 * is in-memory unless a test needs a second connection, which only a file gives. [preferences]
 * are over [preferenceValues]; a second device over the same map is the same install started
 * again. No fixture names a real source: `CONTRIBUTING.md` binds test data too. [remote] is what
 * the engine syncs through: [backend], unless a test runs the engine over another store, as the
 * WebDAV backend's test does over the sync fixture. [autoDownload] is what the engine hands the
 * chapters a pull inserted to, a [RecordingAutoDownload] unless a test builds another over the
 * device.
 */
internal class SyncTestDevice(
    val backend: InMemorySyncBackend,
    url: String = JdbcSqliteDriver.IN_MEMORY,
    dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    val preferenceValues: MutableMap<String, Any> = mutableMapOf(),
    storeWrapper: (SyncStore) -> SyncStore = { it },
    exported: Boolean = false,
    val remote: SyncBackend = backend,
    autoDownloadOf: (SyncTestDevice) -> AutoDownload = ::RecordingAutoDownload,
) : AutoCloseable {

    val driver = JdbcSqliteDriver(
        url,
        Properties().apply { setProperty("foreign_keys", "true") },
        Database.Schema.synchronous(),
    )

    val database = Database(
        driver = driver,
        chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
        historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
        mangasAdapter = Mangas.Adapter(
            genreAdapter = StringListColumnAdapter,
            update_strategyAdapter = UpdateStrategyColumnAdapter,
            memoAdapter = MemoColumnAdapter,
        ),
    )

    val handler: DatabaseHandler = AndroidDatabaseHandler(database, dispatcher)
    val store: SyncStore = storeWrapper(SyncStoreImpl(handler, driver))
    val changeLog = SyncChangeLogRepositoryImpl(handler)
    val aliases = SourceAliasRepositoryImpl(handler)
    val preferences = SyncPreferences(MapPreferenceStore(preferenceValues))
    val restorePreferences = MapPreferenceStore(preferenceValues).let { store ->
        SyncRestorePreferences(store) { key, value -> store.getBoolean(key, false).set(value) }
    }
    val autoDownload: AutoDownload = autoDownloadOf(this)
    val mergedGroups = MergedGroupRepositoryImpl(handler)
    val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
    val engine = SyncEngine(
        store, changeLog, aliases, preferences, restorePreferences, MergeEngine(), autoDownload,
        ReconcileMergedRead(mergedGroups, libraryPreferences),
    )

    /** The calls the default [autoDownload] recorded. */
    val pulledChapters: List<List<Long>> get() = (autoDownload as RecordingAutoDownload).pulled

    init {
        // The in-memory url has one connection, which the property may not reach before the schema.
        exec("PRAGMA foreign_keys = ON")
        // A device whose first push, a full export, is taken as made: its pushes are segments from
        // lastPushedSeq, for the tests of the segment path alone.
        if (exported) preferences.exportedBackend.set(remote.id)
    }

    fun sync(): SyncRunResult? = runBlocking { engine.run(remote) }

    /**
     * Marks every row of [deviceId] up to [seq] applied, as a finished full export at [seq] would,
     * for a reader of a device built with `exported = true`.
     */
    fun startReading(deviceId: String, seq: Long = 0) {
        preferences.pullCursors.set(preferences.pullCursors.get() + (deviceId to seq))
    }

    val deviceId: String get() = preferences.deviceId.get()

    fun exec(sql: String) {
        driver.execute(null, sql.trimIndent(), 0).value
    }

    fun long(sql: String): Long? = driver.executeQuery(
        identifier = null,
        sql = sql.trimIndent(),
        mapper = { cursor: SqlCursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
        parameters = 0,
    ).value

    fun string(sql: String): String? = driver.executeQuery(
        identifier = null,
        sql = sql.trimIndent(),
        mapper = { cursor: SqlCursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
        parameters = 0,
    ).value

    /** Every row of [sql], each column as text, for comparing two devices' tables. */
    fun rows(sql: String, columns: Int): List<List<String?>> = driver.executeQuery(
        identifier = null,
        sql = sql.trimIndent(),
        mapper = { cursor: SqlCursor ->
            val rows = mutableListOf<List<String?>>()
            while (cursor.next().value) rows += (0 until columns).map { cursor.getString(it) }
            QueryResult.Value(rows)
        },
        parameters = 0,
    ).value

    fun logCount(): Long = long("SELECT count(*) FROM sync_change_log")!!

    fun latestSeq(): Long = long("SELECT coalesce(max(seq), 0) FROM sync_change_log")!!

    fun mangaId(source: Long, url: String): Long? =
        long("SELECT _id FROM mangas WHERE source = $source AND url = '$url'")

    fun chapterId(mangaId: Long, url: String): Long? =
        long("SELECT _id FROM chapters WHERE manga_id = $mangaId AND url = '$url'")

    fun addFavourite(id: Long, url: String = "/series/$id", source: Long = SOURCE, title: String = "Title $id") = exec(
        """
        INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, chapter_flags,
            cover_last_modified, date_added, memo)
        VALUES ($id, $source, '$url', '$title', 0, 1, 0, 0, 0, 0, 1000, CAST('{}' AS BLOB))
        """,
    )

    fun addChapter(id: Long, mangaId: Long, url: String = "/series/$mangaId/chapter/$id", order: Long = id) = exec(
        """
        INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, last_page_read,
            chapter_number, source_order, date_fetch, date_upload, memo)
        VALUES ($id, $mangaId, '$url', 'Chapter $id', NULL, 0, 0, 0, $id, $order, 2000, 3000, CAST('{}' AS BLOB))
        """,
    )

    /** A category as the repository creates one: keyed by its name (Q7). */
    fun addCategory(id: Long, name: String, parentId: Long? = null) = exec(
        """
        INSERT INTO categories(_id, name, sort, flags, parent_id, sync_key)
        VALUES ($id, '$name', $id, 0, ${parentId ?: "NULL"}, '${CategorySyncKey.of(name)}')
        """,
    )

    override fun close() {
        driver.close()
    }

    companion object {
        const val SOURCE = 4_242L
    }
}
