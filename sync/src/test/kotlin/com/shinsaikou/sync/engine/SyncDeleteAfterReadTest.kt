package com.shinsaikou.sync.engine

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.data.DatabaseTransactionRunner
import com.shinsaikou.data.chapter.ScanlatorPriorityRepositoryImpl
import com.shinsaikou.data.download.DownloadQueueRepositoryImpl
import com.shinsaikou.domain.chapter.interactor.GetScanlatorPriority
import com.shinsaikou.domain.database.TransactionRunner
import com.shinsaikou.domain.download.ReadChapterCleanup
import com.shinsaikou.domain.download.model.DownloadManifestEntry
import com.shinsaikou.domain.download.model.DownloadQueueEntry
import com.shinsaikou.domain.preferences.DownloadPreferences
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.downloads.AutoDownloadRules
import com.shinsaikou.downloads.DownloadManifest
import com.shinsaikou.downloads.DownloadQueue
import com.shinsaikou.downloads.PendingChapterDeleteStore
import com.shinsaikou.downloads.ReadChapterCleanupRules
import com.shinsaikou.source.model.PageInfo
import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.MapPreferenceStore
import com.shinsaikou.tracking.ApplyResult
import com.shinsaikou.tracking.ApplyRow
import com.shinsaikou.tracking.ApplyTrackChoice
import com.shinsaikou.tracking.CompareChoice
import com.shinsaikou.tracking.CompareSection
import com.shinsaikou.tracking.CompareTrackProgress
import com.shinsaikou.tracking.DelayedTrackingStore
import com.shinsaikou.tracking.EntryRead
import com.shinsaikou.tracking.ListComparingTracker
import com.shinsaikou.tracking.ListEntry
import com.shinsaikou.tracking.ListRead
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.Unconfined
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import tachiyomi.domain.chapter.interactor.FilterChaptersForDownload
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import java.io.File
import java.lang.reflect.Modifier

/**
 * A sync pull and a backup restore never delete a download (phase3-start.md Q20, phase 3 unit DR):
 * the read flag they write does not come from a finish or a mark read on this device, so it reaches
 * neither the reader's staging nor `SetReadStatus`. Q20 governs what triggers a delete, and where
 * the read flag came from does not change that.
 *
 * Device B runs `:downloads`' two rules over its own database, as the app binds them: the engine's
 * [AutoDownloadRules], sync's only way into `:downloads`, and [ReadChapterCleanupRules], with every
 * delete key set to delete (slot 0, after a manual mark read on, bookmarked chapters allowed, no
 * excluded category). Both share one [RecordingManifest] that answers every chapter as downloaded
 * and records every delete. Each test ends with a control: the same rules delete the same chapter
 * when this device's reader finishes it, so the empty record above it is the pull's or the
 * restore's doing and not a rule that cannot delete.
 *
 * "Mark as read" of "Compare with MyAnimeList" (phase 4 unit TC) marks chapters read elsewhere, as a
 * pull does, and deletes nothing either. Device A's [ApplyTrackChoice] is built from its constructor
 * as the app's graph builds it, with A's [ReadChapterCleanup] (the same rules over A's database,
 * recording every call) bound, so whatever reaches for a cleanup, now or later, gets that one, and
 * A's cleanup never hears of the mark. B's pull holds no cleanup (nothing in `:sync` names one); the
 * manifest both devices share records no delete.
 */
class SyncDeleteAfterReadTest {

    @TempDir
    lateinit var dir: File

    private val backend = InMemorySyncBackend(clock = { 0L })
    private val manifest = RecordingManifest()
    private val staged = mutableMapOf<String, Any>()
    private val stagingPreferences = object : PreferenceStore by InMemoryPreferenceStore() {
        override fun getAll(): Map<String, *> = staged.toMap()
    }
    private val store = PendingChapterDeleteStore(stagingPreferences) { key, value ->
        if (value == null) staged.remove(key) else staged[key] = value
    }
    private val downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()).apply {
        removeAfterReadSlots.set(0)
        removeAfterMarkedAsRead.set(true)
        removeBookmarkedChapters.set(true)
        removeExcludeCategories.set(emptySet())
        downloadNewChapters.set(true)
    }

    private val a = SyncTestDevice(backend)
    private val b = SyncTestDevice(backend, autoDownloadOf = ::autoDownloadOver)
    private val cleanup = cleanupOver(b)

    @AfterEach
    fun close() {
        a.close()
        b.close()
    }

    private fun autoDownloadOver(device: SyncTestDevice): AutoDownloadRules {
        val chapters = ChapterRepositoryImpl(device.handler)
        val getChaptersByMangaId = GetChaptersByMangaId(chapters)
        val getManga = GetManga(MangaRepositoryImpl(device.handler))
        return AutoDownloadRules(
            filterChaptersForDownload = FilterChaptersForDownload(
                getChaptersByMangaId,
                downloadPreferences,
                GetCategories(CategoryRepositoryImpl(device.handler)),
            ),
            getNextChapters = GetNextChapters(getChaptersByMangaId, getManga, mockk()),
            getScanlatorPriority = GetScanlatorPriority(ScanlatorPriorityRepositoryImpl(device.handler)),
            getManga = getManga,
            chapterRepository = chapters,
            manifest = manifest,
            queue = TableQueue(device),
            downloadPreferences = downloadPreferences,
            readerPreferences = ReaderPreferences(InMemoryPreferenceStore()),
        )
    }

    private fun cleanupOver(device: SyncTestDevice): ReadChapterCleanupRules {
        val dispatcher = Unconfined
        return ReadChapterCleanupRules(
            chapterRepository = ChapterRepositoryImpl(device.handler),
            getManga = GetManga(MangaRepositoryImpl(device.handler)),
            getCategories = GetCategories(CategoryRepositoryImpl(device.handler)),
            manifest = manifest,
            queue = TableQueue(device),
            downloadPreferences = downloadPreferences,
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            store = store,
            dispatchers = Dispatchers(dispatcher, dispatcher, dispatcher),
            appScope = CoroutineScope(dispatcher),
        )
    }

    /** B's chapter [url] of entry `/series/<mangaId>`. */
    private fun SyncTestDevice.chapterIdOf(mangaId: Long, url: String): Long =
        chapterId(mangaId(SyncTestDevice.SOURCE, "/series/$mangaId")!!, url)!!

    private fun SyncTestDevice.isRead(chapterId: Long): Long? = long("SELECT read FROM chapters WHERE _id = $chapterId")

    /** The control: this device's reader finishes [chapterId] and closes, and the rules delete it. */
    private fun finishAndCloseReader(chapterId: Long) = runBlocking {
        cleanup.chapterFinished(listOf(chapterId))
        cleanup.readerClosed()
    }

    @Test
    fun `a pull that marks a downloaded chapter read, or brings a new one already read, deletes nothing`() {
        a.addFavourite(1)
        a.addChapter(10, mangaId = 1)
        a.sync()
        b.sync().shouldNotBeNull().exportsApplied shouldBe 1
        val kept = b.chapterIdOf(1, "/series/1/chapter/10")
        b.isRead(kept) shouldBe 0

        // A reads and bookmarks chapter 10, and a new chapter 11 arrives read.
        a.exec("UPDATE chapters SET read = 1, bookmark = 1 WHERE _id = 10")
        a.addChapter(11, mangaId = 1)
        a.exec("UPDATE chapters SET read = 1 WHERE _id = 11")
        a.sync()
        b.sync().shouldNotBeNull().segmentsApplied shouldBe 1

        val arrived = b.chapterIdOf(1, "/series/1/chapter/11")
        b.isRead(kept) shouldBe 1
        b.isRead(arrived) shouldBe 1
        manifest.deleted.shouldBeEmpty()
        store.pending() shouldBe emptyMap()

        // A reader closing afterwards finds nothing the pull staged.
        runBlocking { cleanup.readerClosed() }
        manifest.deleted.shouldBeEmpty()

        finishAndCloseReader(kept)
        manifest.deleted shouldBe listOf(kept)
    }

    @Test
    fun `a restore that marks a downloaded chapter read deletes nothing`() {
        b.addFavourite(1, title = "Title 1")
        (1L..4L).forEach { b.addChapter(it, mangaId = 1, url = "/series/1/chapter/$it") }

        // Entry 1 of the generated backup has chapter 1 read and none bookmarked.
        val report = b.restore(syncBackup(entries = 1, chapters = 4).bytes(), File(dir, "b").apply { mkdirs() })

        report.mangaMerged shouldBe 1
        report.mangaFailed shouldBe 0
        b.isRead(1) shouldBe 1
        manifest.deleted.shouldBeEmpty()
        store.pending() shouldBe emptyMap()

        runBlocking { cleanup.readerClosed() }
        manifest.deleted.shouldBeEmpty()

        finishAndCloseReader(1)
        manifest.deleted shouldBe listOf(1L)
    }

    @Test
    fun `mark as read from the tracker comparison deletes no download on either device and queues no push`() {
        a.addFavourite(1)
        (41L..50L).forEach { a.addChapter(it, mangaId = 1) }
        a.exec("UPDATE chapters SET read = 1 WHERE _id <= 45")
        a.exec(
            """
            INSERT INTO manga_sync(manga_id, sync_id, remote_id, library_id, title, last_chapter_read,
                total_chapters, status, score, remote_url, start_date, finish_date, private)
            VALUES (1, $TRACKER, 1001, NULL, 'Entry 1001', 45.0, 0, $READING, 0.0, '/entry/1001', 0, 0, 0)
            """,
        )
        a.sync()
        b.sync().shouldNotBeNull().exportsApplied shouldBe 1
        val bManga = b.mangaId(SyncTestDevice.SOURCE, "/series/1").shouldNotBeNull()
        val onA = RecordingCleanup(cleanupOver(a))
        val queueOnA = trackingStore()
        val queueOnB = trackingStore()

        // The service holds 50: the comparison puts the entry in the "ahead" section with 46 to 50.
        val tracker = mockk<ListComparingTracker>(relaxed = true).also { tracker ->
            every { tracker.id } returns TRACKER
            coEvery { tracker.readList(any()) } returns ListRead.Entries(mapOf(1001L to ListEntry(1001, 50, 0, READING)))
            coEvery { tracker.readEntry(any()) } answers { EntryRead.OnList(firstArg<Track>().copy(lastChapterRead = 50.0)) }
        }
        val tracks = TrackRepositoryImpl(a.handler)
        val chapters = ChapterRepositoryImpl(a.handler)
        val manga = MangaRepositoryImpl(a.handler)
        val compare = CompareTrackProgress(GetFavorites(manga), tracks, chapters, HistoryRepositoryImpl(a.handler), queueOnA)
        val entry = runBlocking {
            val links = compare.links(TRACKER)
            compare.compare(tracker, links, compare.readRemote(tracker, links)).rows.single()
        }
        entry.section shouldBe CompareSection.REMOTE_AHEAD
        entry.toMark.map { it.number } shouldBe listOf(46.0, 47.0, 48.0, 49.0, 50.0)
        val apply = inject(
            ApplyTrackChoice::class.java,
            mapOf(
                TrackRepository::class.java to tracks,
                ChapterRepository::class.java to chapters,
                MangaRepository::class.java to manga,
                TransactionRunner::class.java to DatabaseTransactionRunner(a.handler),
                DelayedTrackingStore::class.java to queueOnA,
                DownloadPreferences::class.java to downloadPreferences,
                ReadChapterCleanup::class.java to onA,
            ),
        )

        runBlocking { apply.run(tracker, listOf(ApplyRow(entry, CompareChoice.MARK_READ))) }
            .results shouldBe listOf(ApplyResult.Done(marked = 5, listed = 5))
        a.sync()
        b.sync().shouldNotBeNull().segmentsApplied shouldBe 1

        a.long("SELECT count(*) FROM chapters WHERE manga_id = 1 AND read = 1") shouldBe 10
        b.long("SELECT count(*) FROM chapters WHERE manga_id = $bManga AND read = 1") shouldBe 10
        manifest.deleted.shouldBeEmpty()
        store.pending() shouldBe emptyMap()
        onA.calls.shouldBeEmpty()
        queueOnA.getItems().shouldBeEmpty()
        queueOnB.getItems().shouldBeEmpty()
        b.string("SELECT last_chapter_read FROM manga_sync WHERE manga_id = $bManga AND sync_id = $TRACKER") shouldBe "50.0"

        // The control: B's reader finishing its 48 deletes it.
        val bChapter48 = b.chapterIdOf(1, "/series/1/chapter/48")
        finishAndCloseReader(bChapter48)
        manifest.deleted shouldBe listOf(bChapter48)
    }

    /**
     * [type] built as the app's graph builds an injected class: each constructor parameter is the
     * value [bindings] holds for its type or, for a concrete class with none, built the same way, so
     * a `SetReadStatus` or `RemoveTrack` is built over the bound repositories and cleanup. An
     * interface or abstract type with no binding fails the test: a dependency the class gains later
     * is wired to what is bound here or named in this map, never left out.
     */
    private fun <T : Any> inject(type: Class<T>, bindings: Map<Class<*>, Any>): T {
        bindings[type]?.let { return type.cast(it) }
        check(!type.isInterface && !Modifier.isAbstract(type.modifiers)) { "no binding for ${type.name}" }
        val constructor = type.constructors.filterNot { it.isSynthetic }.single()
        return type.cast(constructor.newInstance(*constructor.parameterTypes.map { inject(it, bindings) }.toTypedArray()))
    }

    /** A device's delayed tracking queue, over its own preferences. */
    private fun trackingStore(): DelayedTrackingStore {
        val preferences = MapPreferenceStore()
        return DelayedTrackingStore(preferences) { key, value ->
            if (value == null) preferences.values.remove(key) else preferences.values[key] = value
        }
    }

    /** [delegate], recording every call it is given. */
    private class RecordingCleanup(private val delegate: ReadChapterCleanup) : ReadChapterCleanup {
        val calls = mutableListOf<String>()

        override suspend fun chapterFinished(sessionChapterIds: List<Long>) {
            calls += "chapterFinished $sessionChapterIds"
            delegate.chapterFinished(sessionChapterIds)
        }

        override suspend fun readerClosed() {
            calls += "readerClosed"
            delegate.readerClosed()
        }

        override fun markedRead(chapterIds: List<Long>) {
            calls += "markedRead $chapterIds"
            delegate.markedRead(chapterIds)
        }
    }

    /** Every chapter is downloaded; every delete is recorded. Nothing else is asked of it here. */
    private class RecordingManifest : DownloadManifest {
        val deleted = mutableListOf<Long>()

        override suspend fun isDownloaded(chapterId: Long): Boolean = true
        override fun subscribeCountsByManga(): Flow<Map<Long, Long>> = emptyFlow()
        override suspend fun getEntry(chapterId: Long): DownloadManifestEntry? = null
        override suspend fun getPages(chapterId: Long): List<PageInfo>? = null
        override suspend fun getChapterDir(chapterId: Long): File? = null
        override suspend fun delete(chapterIds: List<Long>) {
            deleted += chapterIds
        }
        override suspend fun deleteManga(mangaId: Long) = error("not used")
        override suspend fun getTotalSizeBytes(): Long = 0
    }

    /** The download queue as B's table holds it; nothing is started. */
    private class TableQueue(device: SyncTestDevice) : DownloadQueue {
        private val repository = DownloadQueueRepositoryImpl(device.handler)

        override fun subscribe(): Flow<List<DownloadQueueEntry>> = repository.subscribeAll()
        override val isRunning: StateFlow<Boolean> = MutableStateFlow(false)
        override val isPaused: StateFlow<Boolean> = MutableStateFlow(false)

        override suspend fun enqueue(manga: Manga, chapters: List<Chapter>, autoStart: Boolean) {
            chapters.forEach { repository.enqueue(chapterId = it.id, mangaId = manga.id, now = 0L) }
        }

        override suspend fun start() = Unit
        override suspend fun remove(chapterIds: List<Long>) = repository.remove(chapterIds)
        override suspend fun removeManga(mangaId: Long) = error("not used")
        override suspend fun reorder(orderedChapterIds: List<Long>) = error("not used")
        override suspend fun pause() = error("not used")
        override suspend fun clear() = error("not used")
    }

    private companion object {
        const val TRACKER = 1L
        const val READING = 1L
    }
}
