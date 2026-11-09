package com.shinsaikou.backup.fixture

import android.content.Context
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.shinsaikou.backup.export.BackupExporter
import com.shinsaikou.backup.export.BackupLibraryReader
import com.shinsaikou.backup.export.BackupScheduler
import com.shinsaikou.backup.importer.BackupDatabaseWriter
import com.shinsaikou.backup.importer.BackupImporter
import com.shinsaikou.backup.importer.ImportCheckpointStore
import com.shinsaikou.backup.importer.PreferenceImportCheckpointStore
import com.shinsaikou.backup.importer.PreferenceRestorer
import com.shinsaikou.data.AndroidDatabaseHandler
import com.shinsaikou.data.DatabaseHandler
import com.shinsaikou.data.extension.ExtensionStoreRepositoryImpl
import com.shinsaikou.data.merged.MergedGroupRepositoryImpl
import com.shinsaikou.data.source.SourceAliasRepositoryImpl
import com.shinsaikou.domain.category.interactor.SetCategoryParent
import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.domain.merged.interactor.CarryMergedTracks
import com.shinsaikou.domain.merged.interactor.ReconcileMergedRead
import com.shinsaikou.domain.preferences.BackupPreferences
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.SyncRestorePreferences
import com.shinsaikou.domain.preferences.TrackPreferences
import com.shinsaikou.libraryupdate.LibraryUpdateScheduler
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.Source
import com.shinsaikou.tracking.TrackerManager
import com.shinsaikou.tracking.myanimelist.MyAnimeList
import com.shinsaikou.tracking.myanimelist.MyAnimeListClient
import com.shinsaikou.tracking.myanimelist.MyAnimeListEndpoints
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.BackupHeader
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import okio.source
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.FetchInterval
import java.io.File
import java.io.IOException
import java.util.Properties
import kotlin.time.Clock

/**
 * Schema version 1 on the in-memory JVM driver, behind the real [DatabaseHandler] and the real
 * `:data` repositories. The dispatcher is [Dispatchers.Unconfined] so a `runTest` body sees
 * writes without idling the IO pool.
 *
 * [handlerFactory] lets a test wrap the handler, which is how the resume and the batch fallback
 * cases make a chunk transaction fail without corrupting anything. [decoderFactory] does the same
 * for the decoder, which is how a test makes the entries fail after the header has read them.
 */
class BackupTestEnvironment(
    private val filesDir: File,
    sources: List<Source> = defaultSources(),
    handlerFactory: (DatabaseHandler) -> DatabaseHandler = { it },
    decoderFactory: (BackupDecoder) -> BackupDecoder = { it },
) : AutoCloseable {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), Database.Schema.synchronous())

    val database: Database = run {
        driver.execute(null, "PRAGMA foreign_keys = ON", 0).value
        Database(
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
    }

    val handler: DatabaseHandler = handlerFactory(AndroidDatabaseHandler(database, Dispatchers.Unconfined))

    val json = Json { ignoreUnknownKeys = true }
    val preferenceStore = FakePreferenceStore()
    val sourcePreferenceFiles = mutableMapOf<String, FakeSharedPreferences>()
    val stubSourceNames = FakeStubSourceNames()
    val sourceManager = FakeSourceManager(sources)

    val context: Context = mockk<Context>().also { mock ->
        every { mock.filesDir } returns filesDir
        every { mock.cacheDir } returns File(filesDir, "cache")
        every { mock.getSharedPreferences(any(), any()) } answers {
            sourcePreferenceFiles.getOrPut(firstArg()) { FakeSharedPreferences() }
        }
    }

    val libraryPreferences = LibraryPreferences(preferenceStore)
    val backupPreferences = BackupPreferences(preferenceStore)
    /** Each write of the restore's sync flag, in order, as the app's committing writer takes it. */
    val restoreFlagCommits = mutableListOf<Pair<String, Boolean>>()

    /** When true, the flag's writer throws as a preference file that refuses a commit does. */
    var refuseRestoreFlag = false

    /** Called as the flag's writer is asked to commit, so a test can see what the run left on disk then. */
    var beforeRestoreFlagCommit: () -> Unit = {}
    val syncRestorePreferences = SyncRestorePreferences(preferenceStore) { key, value ->
        beforeRestoreFlagCommit()
        if (refuseRestoreFlag) throw IOException("fixture: the preference file refused the commit of $key")
        restoreFlagCommits += key to value
        preferenceStore.getBoolean(key, false).set(value)
    }

    val mangaRepository = MangaRepositoryImpl(handler)
    val chapterRepository = ChapterRepositoryImpl(handler)
    val categoryRepository = CategoryRepositoryImpl(handler)
    val historyRepository = HistoryRepositoryImpl(handler)
    val trackRepository = TrackRepositoryImpl(handler)

    /**
     * The trackers the app graph holds (phase 3 unit T2): MyAnimeList alone, signed out, on its own
     * endpoints with a blank client id, as a worktree's build has it. The validator only asks the
     * manager which ids it serves and whether each is signed in; nothing here calls a service.
     */
    val trackerManager = TrackerManager(
        setOf(
            MyAnimeList(
                trackPreferences = TrackPreferences(preferenceStore),
                trackRepository = trackRepository,
                httpClient = mockk(),
                clock = Clock.System,
                endpointsProvider = { MyAnimeListEndpoints.MYANIMELIST },
                buildClient = MyAnimeListClient(""),
                json = Json,
            ),
        ),
    )
    val extensionStoreRepository = ExtensionStoreRepositoryImpl(handler)
    val sourceAliasRepository = SourceAliasRepositoryImpl(handler)
    val mergedGroupRepository = MergedGroupRepositoryImpl(handler)
    /** The importer's merged read reconcile, real, and spied on so a test can see it asked. */
    val reconcileMergedRead: ReconcileMergedRead by lazy {
        spyk(ReconcileMergedRead(mergedGroupRepository, libraryPreferences))
    }

    val decoder = decoderFactory(BackupDecoder(context, ProtoBuf))
    val validator = BackupFileValidator(decoder, sourceManager, trackerManager)
    val writer = BackupDatabaseWriter(handler)
    val preferenceRestorer = PreferenceRestorer(context, preferenceStore, extensionStoreRepository)
    val checkpoints: ImportCheckpointStore =
        PreferenceImportCheckpointStore(context, backupPreferences, json)

    val sourceExtensionInstaller = FakeSourceExtensionInstaller()
    val customCovers = FakeCustomCoverFiles()
    val libraryUpdateScheduler = mockk<LibraryUpdateScheduler>(relaxed = true)
    val backupScheduler = mockk<BackupScheduler>(relaxed = true)

    private val fetchInterval = FetchInterval(GetChaptersByMangaId(chapterRepository))


    val importer = BackupImporter(
        decoder = decoder,
        validator = validator,
        handler = handler,
        writer = writer,
        categoryRepository = categoryRepository,
        extensionStoreRepository = extensionStoreRepository,
        sourceAliasRepository = sourceAliasRepository,
        stubSourceNames = stubSourceNames,
        sourceManager = sourceManager,
        preferenceRestorer = preferenceRestorer,
        updateManga = UpdateManga(mangaRepository, fetchInterval),
        fetchInterval = fetchInterval,
        libraryPreferences = libraryPreferences,
        checkpoints = checkpoints,
        libraryUpdateScheduler = libraryUpdateScheduler,
        backupScheduler = backupScheduler,
        syncRestorePreferences = syncRestorePreferences,
        sourceExtensionInstaller = sourceExtensionInstaller,
        reconcileMergedRead = reconcileMergedRead,
        mergedGroupRepository = mergedGroupRepository,
        carryMergedTracks = CarryMergedTracks(trackRepository),
        setCategoryParent = SetCategoryParent(categoryRepository),
        customCovers = customCovers,
        json = json,
    )

    val exporter = BackupExporter(
        parser = ProtoBuf,
        reader = BackupLibraryReader(handler),
        categoryRepository = categoryRepository,
        extensionStoreRepository = extensionStoreRepository,
        sourceManager = sourceManager,
        stubSourceNames = stubSourceNames,
        preferenceStore = preferenceStore,
        context = context,
        decoder = decoder,
        mergedGroupRepository = mergedGroupRepository,
        customCovers = customCovers,
    )

    /** The [Backup] an export with [options] writes, staged and read back whole. */
    suspend fun export(options: BackupOptions): Backup = exporter.build(options).use { decoder.decode(it.file.source()) }

    /** The bytes an export with [options] writes, as its target receives them. */
    suspend fun exportBytes(options: BackupOptions): ByteArray = exporter.build(options).use { staged ->
        Buffer().also { exporter.write(staged, it) }.readByteArray()
    }

    /** [backup]'s header as the importer reads it, through the file upstream would write. */
    fun header(backup: Backup): BackupHeader = decoder.header(Buffer().write(SyntheticBackupFixture.tachibk(backup)))

    /** Writes [bytes] where the importer expects the private copy to be. */
    fun writeImportFile(bytes: ByteArray): File = checkpoints.importFile.also {
        it.parentFile?.mkdirs()
        it.writeBytes(bytes)
    }

    fun count(table: String): Long {
        var result = 0L
        driver.executeQuery(null, "SELECT count(*) FROM $table", { cursor ->
            cursor.next()
            result = cursor.getLong(0)!!
            QueryResult.Unit
        }, 0).value
        return result
    }

    /** Every table the schema creates, SQLite's own excluded. */
    fun tables(): List<String> = query(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
    ) { it.getString(0)!! }

    /** [table]'s columns in declaration order, with the storage class each is declared with. */
    fun columns(table: String): List<Pair<String, String>> =
        query("PRAGMA table_info($table)") { it.getString(1)!! to it.getString(2)!!.uppercase() }

    /**
     * Every row of [table] as stored, keyed by column name: a Long, Double, String or ByteArray by the
     * column's declared type, or null. Read with SQL rather than through a repository, so what a test
     * compares against is the row and not a mapper's view of it.
     */
    fun rows(table: String): List<Map<String, Any?>> {
        val columns = columns(table)
        return query("SELECT ${columns.joinToString { it.first }} FROM $table ORDER BY rowid") { cursor ->
            columns.withIndex().associate { (index, column) ->
                column.first to when (column.second) {
                    "INTEGER" -> cursor.getLong(index)
                    "REAL" -> cursor.getDouble(index)
                    "BLOB" -> cursor.getBytes(index)
                    else -> cursor.getString(index)
                }
            }
        }
    }

    /** Inserts one row with exactly [values], bound by type, so a test can set columns no repository writes. */
    fun insert(table: String, values: Map<String, Any?>) {
        val names = values.keys.toList()
        driver.execute(
            null,
            "INSERT INTO $table(${names.joinToString()}) VALUES (${names.joinToString { "?" }})",
            names.size,
        ) {
            names.forEachIndexed { index, name ->
                when (val value = values[name]) {
                    null -> bindString(index, null)
                    is Long -> bindLong(index, value)
                    is Int -> bindLong(index, value.toLong())
                    is Double -> bindDouble(index, value)
                    is Boolean -> bindLong(index, if (value) 1L else 0L)
                    is String -> bindString(index, value)
                    is ByteArray -> bindBytes(index, value)
                    else -> error("cannot bind $name = $value (${value::class})")
                }
            }
        }.value
    }

    private fun <T> query(sql: String, read: (SqlCursor) -> T): List<T> {
        val result = mutableListOf<T>()
        driver.executeQuery(null, sql, { cursor ->
            while (cursor.next().value) result += read(cursor)
            QueryResult.Unit
        }, 0).value
        return result
    }

    override fun close() {
        driver.close()
    }

    companion object {
        fun defaultSources(): List<FakeSource> = listOf(
            FakeSource(SyntheticBackupFixture.SOURCE_A, SyntheticBackupFixture.SOURCE_A_NAME),
            FakeSource(SyntheticBackupFixture.SOURCE_B, SyntheticBackupFixture.SOURCE_B_NAME),
            FakeSource(SourceIds.LOCAL, "Local source", lang = "other"),
        )
    }
}
