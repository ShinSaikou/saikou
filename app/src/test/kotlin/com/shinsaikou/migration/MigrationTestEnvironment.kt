package com.shinsaikou.migration

import android.content.Context
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.shinsaikou.data.AndroidDatabaseHandler
import com.shinsaikou.data.DatabaseTransactionRunner
import com.shinsaikou.data.download.DownloadManifestRepositoryImpl
import com.shinsaikou.domain.chapter.interactor.GetExcludedScanlators
import com.shinsaikou.domain.chapter.interactor.SetReadStatus
import com.shinsaikou.domain.chapter.interactor.SyncChaptersWithSource
import com.shinsaikou.domain.database.TransactionRunner
import com.shinsaikou.domain.download.AutoDownload
import com.shinsaikou.domain.manga.interactor.RefreshMangaFromSource
import com.shinsaikou.domain.manga.interactor.SetMangaDetailsOverride
import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.domain.track.TrackProgress
import com.shinsaikou.downloads.DownloadManifest
import com.shinsaikou.downloads.DownloadQueue
import com.shinsaikou.downloads.MigrationDownloads
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.sync.MapPreferenceStore
import com.shinsaikou.tracking.DelayedTrackingScheduler
import com.shinsaikou.tracking.DelayedTrackingStore
import com.shinsaikou.ui.fixture.RecordingReadChapterCleanup
import com.shinsaikou.ui.fixture.deletingSetReadStatus
import eu.kanade.tachiyomi.data.cache.CoverCache
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import tachiyomi.core.common.preference.InMemoryPreferenceStore
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
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import java.io.File
import java.io.IOException
import java.util.Date
import java.util.Properties
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers

/**
 * [MigrateManga] over the real schema, triggers and `:data` repositories on the in-memory JVM
 * driver, with the real [RefreshMangaFromSource] and [CoverCache] (under [filesDir]) and fakes for
 * the sources, the download queue and manifest, the migration's download transfer, and the tracking
 * retry worker. [driver] and
 * [handler] are open so a sync test can run the engine over the same database (phase 4 unit NB).
 *
 * Every repository write [MigrateManga] makes is recorded in [calls], in order, and [failOn] makes
 * the named write throw, so a test sees the write order and a failure after the first write.
 *
 * [setReadStatus], [cleanup], [autoDownload] and [trackProgress] are what a migration must never
 * reach (R97 item 3, R99 item 7): a real [SetReadStatus] with "After manually marked as read" on,
 * whose hand-offs land in [cleanup], and recording fakes. The product takes none of them; a break
 * that routes the copy through one of them is wired here and caught by the tests that read them.
 */
internal class MigrationTestEnvironment(val filesDir: File) : AutoCloseable {

    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), Database.Schema.synchronous())

    private val database: Database = run {
        driver.execute(null, "PRAGMA foreign_keys = ON", 0).value
        Database(
            driver = driver,
            chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
            historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
            ),
        )
    }

    val handler = AndroidDatabaseHandler(database, Dispatchers.Unconfined)
    private val dispatchers = AppDispatchers(io = Dispatchers.Unconfined, default = Dispatchers.Unconfined, main = Dispatchers.Unconfined)
    val transactionRunner = DatabaseTransactionRunner(handler)

    /** Every repository write, in order, as `<kind> <detail>`. */
    val calls = mutableListOf<String>()

    /** A write whose `<kind>` equals this throws, after it is recorded. */
    var failOn: String? = null

    /** Makes the favourite write answer false after it is recorded, as the repository does on a failure it logs. */
    var mangaWriteAnswersFalse = false

    /** Runs at the start of the migration's transaction, before its first read. */
    var beforeMigrationTransaction: (suspend () -> Unit)? = null

    /** The chapter updates the migration wrote, as it built them. */
    val chapterUpdates = mutableListOf<ChapterUpdate>()

    val mangaRepository: MangaRepository = RecordingMangaRepository(MangaRepositoryImpl(handler))
    val chapterRepository: ChapterRepository = RecordingChapterRepository(ChapterRepositoryImpl(handler))
    val categoryRepository: CategoryRepository = CategoryRepositoryImpl(handler)
    val trackRepository: TrackRepository = RecordingTrackRepository(TrackRepositoryImpl(handler))
    val historyRepository: HistoryRepository = RecordingHistoryRepository(HistoryRepositoryImpl(handler))

    val preferenceStore = InMemoryPreferenceStore()
    val sourcePreferences = SourcePreferences(preferenceStore)
    private val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())

    /** Source id to the fake a test registered with [source]; anything else is not installed. */
    private val sources = mutableMapOf<Long, Source>()
    val sourceManager: SourceManager = mockk<SourceManager>().also { manager ->
        every { manager.get(any()) } answers { sources[firstArg()] }
    }

    val downloadQueue: DownloadQueue = mockk(relaxed = true)
    val downloadManifest: DownloadManifest = mockk(relaxed = true)

    /**
     * Moves nothing and finds no download unless a test says otherwise; `MigrationDownloadsTest`
     * covers the moves and the downloaded chapters themselves.
     */
    val migrationDownloads: MigrationDownloads = mockk(relaxed = true) {
        coEvery { transfer(any(), any(), any()) } returns MigrationDownloads.Outcome.NONE
        coEvery { downloadedChapters(any()) } returns emptyList()
    }

    val context: Context = mockk { every { filesDir } returns this@MigrationTestEnvironment.filesDir }
    val coverCache = CoverCache(context)

    val trackingPreferences = MapPreferenceStore()

    /** Makes every tracker queue write throw, as the committing writer does when the disk refuses it. */
    var trackingWriteFails = false
    val delayedTrackingStore = DelayedTrackingStore(trackingPreferences) { key, value ->
        if (trackingWriteFails) throw IOException("fixture: tracking queue")
        if (value == null) trackingPreferences.values.remove(key) else trackingPreferences.values[key] = value
    }
    var schedules = 0
    val delayedTrackingScheduler = DelayedTrackingScheduler { schedules++ }

    val cleanup = RecordingReadChapterCleanup()
    val setReadStatus: SetReadStatus = deletingSetReadStatus(chapterRepository, cleanup, mangaRepository)
    val autoDownload = RecordingAutoDownload()
    val trackProgress = RecordingTrackProgress()

    private val getChaptersByMangaId = GetChaptersByMangaId(chapterRepository)
    private val fetchInterval = FetchInterval(getChaptersByMangaId)
    private val updateManga = UpdateManga(mangaRepository, fetchInterval)

    val refreshMangaFromSource = RefreshMangaFromSource(
        getMangaWithChapters = GetMangaWithChapters(mangaRepository, chapterRepository),
        updateManga = updateManga,
        syncChaptersWithSource = SyncChaptersWithSource(
            chapterRepository = chapterRepository,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = updateManga,
            getChaptersByMangaId = getChaptersByMangaId,
            transactionRunner = transactionRunner,
            downloadManifestRepository = DownloadManifestRepositoryImpl(handler),
            libraryPreferences = libraryPreferences,
            getExcludedScanlators = GetExcludedScanlators(mangaRepository),
            historyRepository = HistoryRepositoryImpl(handler),
        ),
        fetchInterval = fetchInterval,
        libraryPreferences = libraryPreferences,
        dispatchers = dispatchers,
    )

    val migrateManga = MigrateManga(
        sourcePreferences = sourcePreferences,
        sourceManager = sourceManager,
        refreshMangaFromSource = refreshMangaFromSource,
        transactionRunner = object : TransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T = transactionRunner.inTransaction {
                beforeMigrationTransaction?.invoke()
                block()
            }
        },
        mangaRepository = mangaRepository,
        setMangaDetailsOverride = SetMangaDetailsOverride(mangaRepository),
        chapterRepository = chapterRepository,
        categoryRepository = categoryRepository,
        trackRepository = trackRepository,
        historyRepository = historyRepository,
        coverCache = coverCache,
        downloadQueue = downloadQueue,
        downloadManifest = downloadManifest,
        migrationDownloads = migrationDownloads,
        delayedTrackingStore = delayedTrackingStore,
        delayedTrackingScheduler = delayedTrackingScheduler,
        dispatchers = dispatchers,
    )

    /**
     * Registers an installed source whose chapter list is [chapters] (url, name, number), and
     * returns it; every call it answers is counted in [FakeSource.detailsCalls] and
     * [FakeSource.chapterCalls].
     */
    fun source(id: Long, chapters: List<ChapterInfo>): FakeSource = FakeSource(id, chapters).also { sources[id] = it }

    /** Inserts an entry as a network row, then applies [update] to it, and returns the row as stored. */
    suspend fun manga(
        source: Long,
        url: String,
        title: String,
        update: (MangaUpdate) -> MangaUpdate = { it },
    ): Manga {
        val row = mangaRepository.insertNetworkManga(
            listOf(Manga.create().copy(source = source, url = url, title = title, initialized = true)),
        ).single()
        mangaRepository.update(update(MangaUpdate(id = row.id)))
        calls.clear()
        return mangaRepository.getMangaById(row.id)
    }

    /**
     * Inserts [chapters] for [mangaId], each at its index as its source order, the order a source
     * listing them as [info] would store them in, and returns them as stored.
     */
    suspend fun chapters(mangaId: Long, vararg chapters: Chapter): List<Chapter> =
        chapterRepository.addAll(chapters.mapIndexed { index, chapter -> chapter.copy(mangaId = mangaId, sourceOrder = index.toLong()) })

    suspend fun category(name: String): Category {
        categoryRepository.insert(Category(id = 0, name = name, order = 0, flags = 0))
        return categoryRepository.getAll().single { it.name == name }
    }

    suspend fun track(mangaId: Long, trackerId: Long, remoteId: Long, lastChapterRead: Double = 0.0) {
        trackRepository.insert(
            Track(
                id = 0,
                mangaId = mangaId,
                trackerId = trackerId,
                remoteId = remoteId,
                libraryId = null,
                title = "Tracked $remoteId",
                lastChapterRead = lastChapterRead,
                totalChapters = 0,
                status = 1,
                score = 0.0,
                remoteUrl = "",
                startDate = 0,
                finishDate = 0,
                private = false,
            ),
        )
        calls.clear()
    }

    /** One column of one row, as SQLite returns it. */
    fun long(sql: String): Long? {
        var result: Long? = null
        driver.executeQuery(null, sql, { cursor ->
            if (cursor.next().value) result = cursor.getLong(0)
            QueryResult.Unit
        }, 0).value
        return result
    }

    /**
     * What a migration's transaction may write, as text: every entry's favourite, date added,
     * display pair, notes, position and clocks, every chapter's progress, and every category and
     * track row. Two equal snapshots mean the transaction left nothing behind.
     */
    fun snapshot(): List<String> = snapshot(mangaId = null)

    /** [snapshot] limited to one entry's rows. */
    fun snapshot(mangaId: Long?): List<String> = buildList {
        val id = mangaId?.let { "_id = $it" } ?: "1"
        val owner = mangaId?.let { "manga_id = $it" } ?: "1"
        addAll(
            rows(
                "SELECT _id, favorite, date_added, viewer, chapter_flags, notes, manual_position, deleted_at, " +
                    "favorite_modified_at, display_modified_at, notes_modified_at, position_modified_at, " +
                    "categories_modified_at, trackers_modified_at, restored_at FROM mangas WHERE $id ORDER BY _id",
                15,
            ),
        )
        addAll(rows("SELECT _id, manga_id, read, bookmark, date_fetch, last_page_read FROM chapters WHERE $owner ORDER BY _id", 6))
        addAll(rows("SELECT manga_id, category_id FROM mangas_categories WHERE $owner ORDER BY manga_id, category_id", 2))
        addAll(
            rows(
                "SELECT manga_id, sync_id, remote_id, last_chapter_read FROM manga_sync WHERE $owner ORDER BY manga_id, sync_id",
                4,
            ),
        )
    }

    /** Runs one statement on the database, outside the repositories. */
    fun execute(sql: String) {
        driver.execute(null, sql, 0).value
    }

    private fun rows(sql: String, columns: Int): List<String> {
        val result = mutableListOf<String>()
        driver.executeQuery(null, sql, { cursor ->
            while (cursor.next().value) {
                result += (0 until columns).joinToString("|") { cursor.getString(it) ?: "null" }
            }
            QueryResult.Unit
        }, 0).value
        return result
    }

    override fun close() {
        driver.close()
    }

    private fun record(kind: String, detail: String) {
        calls += "$kind $detail"
        if (failOn == kind) throw IOException("fixture: $kind refused")
    }

    private inner class RecordingMangaRepository(private val real: MangaRepository) : MangaRepository by real {
        override suspend fun setMangaCategories(mangaId: Long, categoryIds: List<Long>) {
            record("categories", "$mangaId $categoryIds")
            real.setMangaCategories(mangaId, categoryIds)
        }

        override suspend fun undelete(mangaId: Long) {
            record("undelete", "$mangaId")
            real.undelete(mangaId)
        }

        override suspend fun setDetailsOverride(mangaId: Long, titleOverride: String?, descriptionOverride: String?): Boolean {
            record("details", "$mangaId")
            return real.setDetailsOverride(mangaId, titleOverride, descriptionOverride)
        }

        override suspend fun updateAll(mangaUpdates: List<MangaUpdate>): Boolean {
            record("manga", mangaUpdates.joinToString { "${it.id} favorite=${it.favorite}" })
            if (mangaWriteAnswersFalse) return false
            return real.updateAll(mangaUpdates)
        }
    }

    private inner class RecordingChapterRepository(private val real: ChapterRepository) : ChapterRepository by real {
        override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>) {
            record("chapters", chapterUpdates.joinToString { it.id.toString() })
            this@MigrationTestEnvironment.chapterUpdates += chapterUpdates
            real.updateAll(chapterUpdates)
        }
    }

    private inner class RecordingTrackRepository(private val real: TrackRepository) : TrackRepository by real {
        override suspend fun insert(track: Track) {
            record("track", "insert ${track.mangaId} ${track.trackerId} ${track.remoteId}")
            real.insert(track)
        }

        override suspend fun rebind(track: Track) {
            record("track", "rebind ${track.mangaId} ${track.trackerId} ${track.remoteId}")
            real.rebind(track)
        }
    }

    private inner class RecordingHistoryRepository(private val real: HistoryRepository) : HistoryRepository by real {
        override suspend fun mergeHistory(chapterId: Long, readAt: Date, readDuration: Long) {
            record("history", "merge $chapterId")
            real.mergeHistory(chapterId, readAt, readDuration)
        }

        override suspend fun deleteHistoryByChapterIds(chapterIds: List<Long>) {
            record("history", "delete $chapterIds")
            real.deleteHistoryByChapterIds(chapterIds)
        }
    }

    /** A source that answers from a fixed chapter list and counts its calls. */
    class FakeSource(override val id: Long, private val chapters: List<ChapterInfo>) : Source by mockk() {
        override val name: String = "Fixture source $id"
        override val lang: String = "en"
        var detailsCalls = 0
        var chapterCalls = 0

        override suspend fun getMangaDetails(manga: MangaInfo): MangaInfo {
            detailsCalls++
            return manga.copy(initialized = true)
        }

        override suspend fun getChapterList(manga: MangaInfo): List<ChapterInfo> {
            chapterCalls++
            return chapters
        }

        /** The contract's default, which the delegated mock would not run, over the two counted calls. */
        override suspend fun getMangaUpdate(
            manga: MangaInfo,
            chapters: List<ChapterInfo>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ): com.shinsaikou.source.model.MangaUpdate = com.shinsaikou.source.model.MangaUpdate(
            manga = if (fetchDetails) getMangaDetails(manga) else null,
            chapters = if (fetchChapters) getChapterList(manga) else null,
        )
    }

    class RecordingAutoDownload : AutoDownload by mockk() {
        val calls = mutableListOf<String>()

        override suspend fun queueNewChapters(manga: Manga, chapters: List<Chapter>, autoStart: Boolean): Int {
            calls += "queueNewChapters ${manga.id} ${chapters.map { it.id }}"
            return chapters.size
        }

        override suspend fun queuePulledChapters(chapterIds: List<Long>): Int {
            calls += "queuePulledChapters $chapterIds"
            return chapterIds.size
        }

        override suspend fun startQueue() {
            calls += "startQueue"
        }
    }

    class RecordingTrackProgress : TrackProgress {
        val recorded = mutableListOf<Pair<Long, Double>>()

        override suspend fun wouldRecord(mangaId: Long, chapterNumber: Double): Boolean = true

        override suspend fun record(mangaId: Long, chapterNumber: Double): Int {
            recorded += mangaId to chapterNumber
            return 1
        }
    }

    companion object {
        fun chapter(url: String, number: Double, read: Boolean = false, bookmark: Boolean = false, dateFetch: Long = 0L) =
            Chapter.create().copy(
                url = url,
                name = "Chapter ${number.toString().removeSuffix(".0")}",
                chapterNumber = number,
                read = read,
                bookmark = bookmark,
                dateFetch = dateFetch,
                sourceOrder = 0,
                dateUpload = 1_000L,
            )

        fun info(chapter: Chapter) = ChapterInfo(
            url = chapter.url,
            name = chapter.name,
            chapterNumber = chapter.chapterNumber.toFloat(),
            dateUpload = chapter.dateUpload,
        )
    }
}
