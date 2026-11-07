package com.shinsaikou.sync

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.shinsaikou.SaikouApp
import com.shinsaikou.di.AppGraph
import com.shinsaikou.domain.download.AutoDownload
import com.shinsaikou.domain.merged.interactor.ReconcileMergedRead
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.SyncRestorePreferences
import com.shinsaikou.domain.sync.model.SyncChange
import com.shinsaikou.domain.sync.model.SyncEntity
import com.shinsaikou.domain.sync.model.SyncPushRows
import com.shinsaikou.domain.sync.repository.SyncApplyScope
import com.shinsaikou.domain.sync.repository.SyncChangeLogRepository
import com.shinsaikou.domain.sync.repository.SyncStore
import com.shinsaikou.sync.format.ChapterRow
import com.shinsaikou.sync.format.RemoteName
import com.shinsaikou.sync.format.SegmentReader
import com.shinsaikou.sync.format.SyncLayout
import com.shinsaikou.util.awaitStartupPrune
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * R87 carry-over 1 on the app's own database (phase 3 unit I, fix round 1 item 2): E2's
 * `SyncEngineRunTest` case "a write to the same row from another coroutine during an apply is
 * logged", run through the process's graph, so the database is the one the application opens
 * (the bundled SQLite driver, WAL). The JVM test shows it on a JDBC file database; what only the
 * device can show is whether the app's driver makes a second writer wait for the apply's
 * transaction, or lets it join the apply's connection, where the latch would silence its triggers
 * and the write would never travel.
 *
 * Two [SyncEngine]s over the app's [SyncStore] and change log, each with preferences of its own in a
 * separate file (so the application's sync state is not touched), share an in-memory backend. The
 * writer engine pushes a segment that moves one chapter's last page; the chapter's last page is set
 * back locally; the reader engine pulls that segment through a store whose apply, after the
 * engine's writes and before the commit, starts a bookmark of the same chapter from another
 * coroutine on another thread, as E2's test does, and holds the transaction open [WAIT_MILLIS].
 * The write must not have finished by then, must finish after the transaction, must be in the
 * change log and in what the reader's push sent, and no latch may stay raised.
 *
 * No argument; nothing is skipped. Every row is under [SOURCE_ID], which no provider serves, and
 * is removed at the end. The engines' own prunes of the change log are left out, so the test reads
 * the second write's row in the log itself. With no backend configured the application's start-up
 * prune empties the log once, right after the process starts; it landed inside this test on
 * `saikou_pixel9_4g` once, so the test waits for it first ([awaitStartupPrune]) and runs the scenario
 * once. It still checks at the end that the last row its setup logged is there, so a row removed
 * during the run fails it rather than reading as a silenced write.
 *
 * The second test is the other side of the same question (phase 5 unit SC6): the library
 * fingerprint's read snapshot ([SyncStoreSnapshot]), on its own connection in a read transaction,
 * must read one state of the database while writes through the app's driver land in the middle of
 * it, and those writes must not wait for it.
 */
@RunWith(AndroidJUnit4::class)
class SyncLatchDeviceTest {

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SaikouApp
    private val graph: AppGraph get() = app.graph

    private val writerThread = Executors.newSingleThreadExecutor()

    @After
    fun cleanUp() {
        runBlocking(Dispatchers.IO) { removeRows() }
        PREFERENCE_FILES.forEach { app.deleteSharedPreferences(it) }
        writerThread.shutdown()
    }

    /** A backend held in memory, shared by the two engines of the test. */
    private class MemoryBackend : SyncBackend {
        private val files = ConcurrentHashMap<String, Pair<ByteArray, Long>>()

        override val id: String = "sync-latch-device-test"

        override suspend fun probe(): BackendStatus = BackendStatus(reachable = true, authenticated = true, message = null)

        override suspend fun list(prefix: String): List<RemoteFile> =
            files.filterKeys { it.startsWith(prefix) }.map { (path, file) -> RemoteFile(path, file.first.size.toLong(), file.second) }

        override suspend fun create(path: String, content: SyncContent) {
            files[path] = content.open().use { it.readBytes() } to System.currentTimeMillis()
        }

        override suspend fun <T> read(path: String, block: suspend (InputStream) -> T): T =
            block(ByteArrayInputStream(files[path]?.first ?: throw FileNotFoundException(path)))

        override suspend fun delete(path: String) {
            files.remove(path)
        }

        fun bytes(path: String): ByteArray? = files[path]?.first
    }

    /** Calls [duringApply] once it is armed, inside the apply's transaction, after the engine's writes. */
    private class HookedStore(private val real: SyncStore) : SyncStore by real {
        @Volatile var duringApply: (() -> Unit)? = null

        override suspend fun <T> apply(block: suspend SyncApplyScope.() -> T): T =
            real.apply { block().also { duringApply?.also { duringApply = null }?.invoke() } }
    }

    /**
     * The app's change log with the engines' prunes left out, so the log still holds, when the test
     * reads it, every row the second write logged, and nothing removes a row during the test once
     * the start-up prune has run.
     */
    private class KeptChangeLog(real: SyncChangeLogRepository) : SyncChangeLogRepository by real {
        override suspend fun prune(upToSeq: Long) = Unit
    }

    private class Engine(val engine: SyncEngine, val preferences: SyncPreferences)

    private fun engine(file: String, store: SyncStore): Engine {
        val sharedPreferences = app.getSharedPreferences(file, Context.MODE_PRIVATE)
        sharedPreferences.edit().clear().commit()
        val preferenceStore = AndroidPreferenceStore(app, sharedPreferences)
        val preferences = SyncPreferences(preferenceStore)
        val engine = SyncEngine(
            store,
            KeptChangeLog(graph.syncChangeLogRepository),
            graph.sourceAliasRepository,
            preferences,
            SyncRestorePreferences(preferenceStore) { key, value -> preferenceStore.getBoolean(key, false).set(value) },
            MergeEngine(),
            NoAutoDownload,
            ReconcileMergedRead(graph.mergedGroupRepository, LibraryPreferences(preferenceStore)),
        )
        return Engine(engine, preferences)
    }

    /**
     * The scenario is the latch, and the rows it pulls name no installed source: nothing it
     * inserts is handed to the app's download queue (phase 3 unit AD).
     */
    private object NoAutoDownload : AutoDownload {
        override suspend fun queueNewChapters(manga: Manga, chapters: List<Chapter>, autoStart: Boolean): Int = 0

        override suspend fun queuePulledChapters(chapterIds: List<Long>): Int = 0

        override suspend fun startQueue() = Unit

        override suspend fun downloadAhead(
            mangaId: Long,
            pageIndex: Int,
            pageCount: Int,
            nextChapterId: Long?,
        ): Int = 0
    }

    /** What the scenario saw; [pruned] when a log row its setup wrote was gone at the end. */
    private class Observed(
        val pruned: Boolean,
        val writerSegments: Int,
        val appliedSegments: Int = 0,
        val rowsWritten: Int = 0,
        val doneWhileOpen: Boolean? = null,
        val landedAfterClose: Long? = null,
        val logged: List<SyncChange> = emptyList(),
        val pushed: List<ChapterRow> = emptyList(),
        val bookmark: Boolean? = null,
        val lastPage: Long? = null,
        val chapterLatch: Long? = null,
        val mangaLatch: Long? = null,
    )

    @Test
    fun aWriteToTheSameChapterFromAnotherThreadDuringAnApplyWaitsForTheCommitAndIsLogged() = runBlocking(Dispatchers.IO) {
        app.awaitStartupPrune()
        val observed = scenario()
        assertFalse("a change log row the setup wrote was removed during the test", observed.pruned)
        assertEquals("the writer engine's segments", 1, observed.writerSegments)
        assertEquals("the reader engine's applied segments", 1, observed.appliedSegments)
        assertTrue("the reader engine wrote no row", observed.rowsWritten > 0)
        assertEquals("the second write finished while the apply's transaction was open", false, observed.doneWhileOpen)
        assertTrue(
            "the second write finished before the apply closed its transaction",
            checkNotNull(observed.landedAfterClose) > 0,
        )
        assertEquals("the chapter's bookmark", true, observed.bookmark)
        assertEquals("the chapter's last page", 3L, observed.lastPage)
        assertTrue("the second write is not in the change log", observed.logged.isNotEmpty())
        assertEquals(
            "the chapter lines the reader pushed (bookmark, last page)",
            listOf(true to 3L),
            observed.pushed.map { it.bookmark to it.lastPageRead },
        )
        assertEquals("the chapter's latch", 0L, observed.chapterLatch)
        assertEquals("the entry's latch", 0L, observed.mangaLatch)
    }

    /**
     * [SNAPSHOT_ENTRIES] favourites of one chapter each under [SOURCE_ID], so the digest reads at
     * least three of its pages of 20. The fingerprint is then read as Sync settings reads it, through
     * [AppGraph.syncStoreSnapshot], with a store that, after the digest's first page, writes through
     * the app's own driver from another thread: the last of those entries' chapter marked read (it is
     * in the digest's last page, read after the writes) and one more favourite (the highest id, so in
     * no page the digest has read). The writes must commit while the snapshot is open, within
     * [SNAPSHOT_WRITE_TIMEOUT_MILLIS]; through `TransactionRunner.inTransaction`, the read before
     * phase 5 unit SC6, they wait on the driver's one writer connection until the digest ends, so
     * they are awaited again only after the read, never inside it, where they could not finish.
     * The digest read meanwhile must equal the one read before the writes, and the one read after
     * must differ and count more rows. Before and after, the digest through the app's driver
     * ([AppGraph.syncStore], nothing else writing) equals the snapshot's, so the snapshot's
     * connection decodes the rows as the app's does.
     */
    @Test
    fun writesDuringTheFingerprintsSnapshotCommitAtOnceAndItReadsTheStateBeforeThem() = runBlocking(Dispatchers.IO) {
        removeRows()
        val mangas = graph.mangaRepository.insertNetworkManga(
            (0 until SNAPSHOT_ENTRIES).map { index ->
                Manga.create().copy(
                    source = SOURCE_ID,
                    url = snapshotUrl(index),
                    title = "Sync snapshot entry $index",
                    favorite = true,
                    initialized = true,
                )
            },
        )
        val chapters = graph.chapterRepository.addAll(
            mangas.map { Chapter.create().copy(mangaId = it.id, url = "${it.url}/c1", name = "Chapter 1", chapterNumber = 1.0) },
        )
        val last = chapters.maxBy { it.mangaId }

        val storeBefore = SyncDigest.of(graph.syncStore)
        val before = graph.syncStoreSnapshot.read { SyncDigest.of(it) }

        var pagesBefore = 0
        var pagesAfter = 0
        var writes: Deferred<*>? = null
        var committedWhileOpen = false
        var writesMillis = -1L
        val during = graph.syncStoreSnapshot.read { store ->
            SyncDigest.of(
                object : SyncStore by store {
                    override suspend fun readLibrary(mangaIds: Collection<Long>): SyncPushRows {
                        val rows = store.readLibrary(mangaIds)
                        if (pagesBefore > 0) {
                            pagesAfter++
                        } else {
                            pagesBefore++
                            val startedAt = SystemClock.elapsedRealtime()
                            val writing = CoroutineScope(writerThread.asCoroutineDispatcher()).async {
                                graph.chapterRepository.update(ChapterUpdate(id = last.id, read = true, lastPageRead = 0))
                                graph.mangaRepository.insertNetworkManga(
                                    listOf(
                                        Manga.create().copy(
                                            source = SOURCE_ID,
                                            url = snapshotUrl(SNAPSHOT_ENTRIES),
                                            title = "Sync snapshot entry written meanwhile",
                                            favorite = true,
                                            initialized = true,
                                        ),
                                    ),
                                )
                            }
                            writes = writing
                            committedWhileOpen = withTimeoutOrNull(SNAPSHOT_WRITE_TIMEOUT_MILLIS) { writing.await() } != null
                            writesMillis = SystemClock.elapsedRealtime() - startedAt
                        }
                        return rows
                    }
                },
            )
        }
        checkNotNull(writes) { "the digest read no page" }.await()
        val after = graph.syncStoreSnapshot.read { SyncDigest.of(it) }
        val storeAfter = SyncDigest.of(graph.syncStore)
        val written = graph.chapterRepository.getChapterById(last.id)

        Log.i(
            TAG,
            "snapshot on API ${Build.VERSION.SDK_INT}, bundled SQLite ${bundledSqliteVersion()}: before $before (through the " +
                "app's driver $storeBefore), during $during after $pagesBefore page(s) then $pagesAfter more, the writes " +
                "committed while it was open=$committedWhileOpen in $writesMillis ms, after $after (through the app's driver " +
                "$storeAfter)",
        )
        assertTrue("the writes waited for the snapshot ($writesMillis ms)", committedWhileOpen)
        assertTrue("the digest read no page after the writes", pagesAfter >= 2)
        assertEquals("the snapshot's digest against the app's driver's, before", storeBefore, before)
        assertEquals("the digest read while the writes landed", before, during)
        assertEquals("the chapter written meanwhile", true, written?.read)
        assertNotEquals("the digest after the writes", before, after)
        assertTrue("the rows after the writes (${after.rows}, ${before.rows} before)", after.rows > before.rows)
        assertEquals("the snapshot's digest against the app's driver's, after", storeAfter, after)
    }

    private suspend fun scenario(): Observed {
        removeRows()
        val backend = MemoryBackend()
        val manga = graph.mangaRepository.insertNetworkManga(
            listOf(
                Manga.create().copy(
                    source = SOURCE_ID,
                    url = MANGA_URL,
                    title = "Sync latch entry",
                    favorite = true,
                    initialized = true,
                ),
            ),
        ).single()
        val chapter = graph.chapterRepository.addAll(
            listOf(
                Chapter.create().copy(mangaId = manga.id, url = "$MANGA_URL/c1", name = "Chapter 1", chapterNumber = 1.0),
            ),
        ).single()
        // The last row the setup logged. Nothing here prunes the log, and the start-up prune has run,
        // so it must still be there at the end.
        val start = graph.syncChangeLogRepository.getLatestSeq()
        suspend fun pruned(): Boolean = graph.syncChangeLogRepository.getChangesAfter(start - 1, 1).firstOrNull()?.seq != start

        // The writer device's segment: the chapter's last page moved to 3.
        val writer = engine(PREFERENCE_FILES[0], graph.syncStore)
        writer.preferences.exportedBackend.set(backend.id)
        writer.preferences.lastPushedSeq.set(start)
        graph.chapterRepository.update(ChapterUpdate(id = chapter.id, lastPageRead = 3))
        val written = checkNotNull(writer.engine.run(backend)) { "the writer engine's run found its lock held" }
        if (written.segmentsPushed != 1) return Observed(pruned(), written.segmentsPushed)

        // Here the last page goes back to 0, so the pull's merge writes the chapter again.
        graph.chapterRepository.update(ChapterUpdate(id = chapter.id, lastPageRead = 0))
        val pushedBefore = graph.syncChangeLogRepository.getLatestSeq()
        val store = HookedStore(graph.syncStore)
        val reader = engine(PREFERENCE_FILES[1], store)
        reader.preferences.exportedBackend.set(backend.id)
        reader.preferences.lastPushedSeq.set(pushedBefore)
        reader.preferences.pullCursors.set(mapOf(writer.preferences.deviceId.get() to start))

        var bookmark: Deferred<Long>? = null
        var doneWhileOpen: Boolean? = null
        var closingAt = 0L
        store.duringApply = {
            val started = CountDownLatch(1)
            bookmark = CoroutineScope(writerThread.asCoroutineDispatcher()).async {
                started.countDown()
                graph.chapterRepository.update(ChapterUpdate(id = chapter.id, bookmark = true))
                SystemClock.elapsedRealtimeNanos()
            }
            started.await(5, TimeUnit.SECONDS)
            // Blocks the apply's own thread, as E2's test does, so the transaction stays open on it.
            Thread.sleep(WAIT_MILLIS)
            doneWhileOpen = bookmark!!.isCompleted
            closingAt = SystemClock.elapsedRealtimeNanos()
        }

        val pulled = checkNotNull(reader.engine.run(backend)) { "the reader engine's run found its lock held" }
        val landedAt = bookmark?.let { withTimeout(LAND_TIMEOUT_MILLIS) { it.await() } }
        // Anything the first push missed, because the write landed after it read the log.
        checkNotNull(reader.engine.run(backend)) { "the reader engine's second run found its lock held" }

        val logged = graph.syncChangeLogRepository.getChangesAfter(pushedBefore, LOG_LIMIT)
            .filter { it.entity == SyncEntity.CHAPTER && it.entityId == chapter.id }
        val pushed = pushedChapters(backend, reader.preferences.deviceId.get(), after = pushedBefore)
        val row = checkNotNull(graph.chapterRepository.getChapterById(chapter.id))
        val observed = Observed(
            pruned = pruned(),
            writerSegments = written.segmentsPushed,
            appliedSegments = pulled.segmentsApplied,
            rowsWritten = pulled.rowsWritten,
            doneWhileOpen = doneWhileOpen,
            landedAfterClose = landedAt?.let { it - closingAt },
            logged = logged,
            pushed = pushed,
            bookmark = row.bookmark,
            lastPage = row.lastPageRead,
            chapterLatch = graph.database.chaptersQueries.getChapterById(chapter.id).awaitAsOne().is_syncing,
            mangaLatch = graph.database.mangasQueries.getMangaById(manga.id).awaitAsOne().is_syncing,
        )
        Log.i(
            TAG,
            "applied segments=${observed.appliedSegments} rows=${observed.rowsWritten}; the second write done while the " +
                "transaction was open=$doneWhileOpen, finished ${observed.landedAfterClose?.div(1_000_000)} ms after " +
                "the apply closed its transaction; logged=${logged.map { "${it.seq}:${it.changeType}" }} after seq " +
                "$pushedBefore; pushed=${pushed.map { "bookmark=${it.bookmark} lastPage=${it.lastPageRead}" }}; " +
                "chapter bookmark=${row.bookmark} lastPage=${row.lastPageRead}; latches " +
                "chapter=${observed.chapterLatch} manga=${observed.mangaLatch}; setup's log row gone=${observed.pruned}",
        )
        return observed
    }

    /** Every chapter line [deviceId] pushed in segments that start at or after [after]. */
    private suspend fun pushedChapters(backend: MemoryBackend, deviceId: String, after: Long): List<ChapterRow> =
        backend.list(SyncLayout.devicePrefix(deviceId))
            .mapNotNull { SyncLayout.parse(it.path) as? RemoteName.Segment }
            .filter { it.afterSeq >= after }
            .flatMap { segment ->
                SegmentReader(ByteArrayInputStream(backend.bytes(segment.path)!!)).use { reader ->
                    generateSequence { reader.next() }.filterIsInstance<ChapterRow>().toList()
                }
            }

    /** The tests' entries and their chapters, gone: a run stopped part way leaves nothing for the next. */
    private suspend fun removeRows() {
        val urls = listOf(MANGA_URL) + (0..SNAPSHOT_ENTRIES).map(::snapshotUrl)
        val mangas = urls.mapNotNull { graph.mangaRepository.getMangaByUrlAndSourceId(it, SOURCE_ID) }
        if (mangas.isEmpty()) return
        mangas.forEach { manga ->
            val chapters = graph.chapterRepository.getChapterByMangaId(manga.id) +
                graph.chapterRepository.getDeletedChaptersByMangaId(manga.id)
            graph.chapterRepository.removeChaptersWithIds(chapters.map { it.id })
            graph.mangaRepository.update(MangaUpdate(id = manga.id, favorite = false))
        }
        graph.mangaRepository.deleteNonLibraryManga(listOf(SOURCE_ID), keepReadManga = false)
    }

    private fun snapshotUrl(index: Int) = "$SNAPSHOT_URL/$index"

    /** The SQLite the app's driver bundles, which is the one on every API level. */
    private fun bundledSqliteVersion(): String = BundledSQLiteDriver().open(":memory:").use { connection ->
        connection.prepare("SELECT sqlite_version()").use { statement -> if (statement.step()) statement.getText(0) else "unknown" }
    }

    private companion object {
        const val TAG = "SaikouSyncLatch"

        /** No provider serves this id, so every row under it belongs to this test alone. */
        const val SOURCE_ID = 9_000_000_000_000_000_006L
        const val MANGA_URL = "/saikou-sync-latch/entry"
        const val SNAPSHOT_URL = "/saikou-sync-latch/snapshot"

        /** Three of the digest's pages of 20, at least. */
        const val SNAPSHOT_ENTRIES = 45

        /** The writes take milliseconds when nothing holds the writer; a digest that holds it, until its end. */
        const val SNAPSHOT_WRITE_TIMEOUT_MILLIS = 10_000L

        val PREFERENCE_FILES = listOf("sync_latch_device_test_writer", "sync_latch_device_test_reader")

        /** How long the apply holds its transaction open after starting the second write. */
        const val WAIT_MILLIS = 1_000L
        const val LAND_TIMEOUT_MILLIS = 30_000L
        const val LOG_LIMIT = 1_000L
    }
}
