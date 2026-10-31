package com.shinsaikou.reader

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.shinsaikou.SaikouApp
import com.shinsaikou.di.AppGraph
import com.shinsaikou.domain.chapter.interactor.SetReadStatus
import com.shinsaikou.domain.chapter.model.PageListEmptyException
import com.shinsaikou.domain.download.model.DownloadManifestEntry
import com.shinsaikou.i18n.R
import com.shinsaikou.reader.pipeline.DownloadedPageLoader
import com.shinsaikou.reader.pipeline.PageState
import com.shinsaikou.reader.state.ReaderEvent
import com.shinsaikou.reader.state.ReaderPhase
import com.shinsaikou.reader.state.ReaderState
import com.shinsaikou.source.SourceNotInstalledException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import logcat.logcat
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * A downloaded chapter opened in the reader while its source is not installed (phase 2 unit H).
 *
 * The rows, the manifest row and the files are all written through the app's own graph: the entry
 * and the chapter through its repositories, the manifest row through
 * `DownloadManifestRepository.recordDownload`, the engine's call, and the pages into the folder
 * `DownloadRoot` names, laid out the way the engine lays them out. Nothing is built for the test
 * (ruling R39).
 *
 * The source id is one no extension and no stub carries, and the first assertion says so, so every
 * branch of the resolver but the downloaded one ends in [SourceNotInstalledException]. A session
 * that reaches Ready with the fixture's page count has therefore read its pages from disk, and the
 * test checks that twice more: the chapter's loader is a [DownloadedPageLoader], and every page's
 * bytes are the bytes of the file written for it, in page order. Page one also has to carry a route,
 * which is the viewer's mark that it went through the page (the reasoning is in [ReaderSmokeTest]).
 *
 * The second test is phase 2 decision Q4 on a device: the same manifest row with its folder gone
 * is not a download, so the chapter goes to its source, which is missing, and the reader fails
 * saying so rather than opening an empty chapter. That reader closes itself with a toast naming the
 * missing source (plan unit K4 item 1), and the test asserts both: the activity reaches DESTROYED
 * before the test closes anything, and the toast's text, read from the accessibility event the
 * platform sends for it, is the one every screen shows for that failure. Since the reader may be
 * gone before `launch` returns, the test takes its view model while the activity is resumed
 * ([launchReader]). The third keeps the folder and removes its pages: the chapter is still a
 * download, its page list is empty, and the reader closes with that failure's own text.
 *
 * The last two are download ahead on a device (phase 3 unit AD): the entry gains chapters 2 to 5,
 * and a page past a quarter of the downloaded chapter 1 queues what upstream's `downloadNextChapters`
 * would when chapter 2 is downloaded too, and nothing when it is not.
 *
 * The three after them are upstream's delete chapters on a device (phase 3 unit DR), every delete
 * setting put back afterwards. With "After reading automatically delete" on the last read chapter,
 * the downloaded chapter read to its last page keeps its files while the reader is open and loses
 * them, folder and manifest row, once the owner leaves it with back. With the default category
 * excluded, the same reading keeps them, since the entry is in no category. And with "After manually
 * marked as read" on, marking it read deletes it: through the graph's `SetReadStatus`, with the call
 * the manga screen's mark read makes (`MangaDetailViewModelTest` pins that call). The manga screen's
 * own mark read, the row's swipe, is driven by `TwoPaneLayoutTest`'s
 * `theMangaScreensSwipeToMarkReadDeletesADownloadedChapterWithAfterManuallyMarkedAsReadOn`.
 *
 * Every name here is synthetic; `CONTRIBUTING.md` binds test data too.
 */
@RunWith(AndroidJUnit4::class)
class DownloadedChapterReaderTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as SaikouApp
    private val graph: AppGraph get() = app.graph

    private var mangaId: Long = -1L
    private var chapterId: Long = -1L
    private lateinit var mangaTitle: String
    private lateinit var chapterDir: File
    private lateinit var pageFiles: List<File>

    /** The download ahead tests' extra manifest rows, and the setting they changed, for [removeTheFixture]. */
    private val aheadChapterIds = mutableListOf<Long>()
    private var aheadSetting: Int? = null

    /** What the delete chapters tests changed, each put back by [removeTheFixture]. */
    private val restoreSettings = mutableListOf<() -> Unit>()

    @Before
    fun seedADownloadedChapterOfAMissingSource() {
        assertNull("the fixture source id is installed on this device", graph.sourceManager.get(SOURCE_ID))

        // Distinct per method, since the entry url is unique per source and a soft-deleted row stays.
        mangaTitle = "$FIXTURE_PREFIX${System.nanoTime()}"
        runBlocking {
            val manga = graph.mangaRepository.insertNetworkManga(
                listOf(Manga.create().copy(source = SOURCE_ID, url = "/fixture/$mangaTitle", title = mangaTitle)),
            ).single()
            mangaId = manga.id

            val chapter = graph.chapterRepository.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = mangaId,
                        url = "/fixture/$mangaTitle/1",
                        name = CHAPTER_NAME,
                        chapterNumber = 1.0,
                    ),
                ),
            ).single()
            chapterId = chapter.id

            chapterDir = File(graph.downloadRoot.mangaDir(SOURCE_DIR_NAME, mangaTitle), CHAPTER_DIR_NAME)
            assertTrue("could not create the chapter folder", chapterDir.mkdirs())
            pageFiles = PAGE_COLORS.mapIndexed { index, color ->
                File(chapterDir, "%03d.png".format(index + 1)).also { writePage(it, color) }
            }

            graph.downloadManifestRepository.recordDownload(
                DownloadManifestEntry(
                    id = 0,
                    chapterId = chapterId,
                    mangaId = mangaId,
                    sourceId = SOURCE_ID,
                    mangaTitle = mangaTitle,
                    mangaUrl = manga.url,
                    chapterUrl = chapter.url,
                    chapterName = chapter.name,
                    scanlator = null,
                    chapterDirName = CHAPTER_DIR_NAME,
                    pageCount = PAGE_COLORS.size.toLong(),
                    sizeBytes = pageFiles.sumOf { it.length() },
                    dateDownloaded = System.currentTimeMillis(),
                ),
            )
        }
    }

    /**
     * The manifest rows go, the source folder with the files, and the rows are soft deleted. So do
     * any queue rows a download ahead test made, and the setting it changed is put back.
     */
    @After
    fun removeTheFixture() {
        runBlocking {
            if (mangaId > 0) graph.downloadQueue.removeManga(mangaId)
            aheadChapterIds.forEach { graph.downloadManifestRepository.remove(listOf(it)) }
            aheadSetting?.let(graph.downloadPreferences.autoDownloadWhileReading::set)
            restoreSettings.forEach { it() }
            if (chapterId > 0) graph.downloadManifestRepository.remove(listOf(chapterId))
            if (mangaId > 0) graph.mangaRepository.softDelete(mangaId, System.currentTimeMillis())
        }
        graph.downloadRoot.sourceDir(SOURCE_DIR_NAME).deleteRecursively()
    }

    @Test
    fun aDownloadedChapterOpensFromDiskWhileItsSourceIsNotInstalled() {
        val (scenario, viewModel) = launchReader(ReaderIntents.newIntent(context, mangaId, chapterId, page = 0))
        try {
            val ready = await(OPEN_TIMEOUT_MILLIS, "the session never became ready") {
                viewModel.state.first { it.phase is ReaderPhase.Ready && it.totalPages > 0 || failed(it) }
            }
            assertTrue("the reader failed: ${ready.phase}", ready.phase is ReaderPhase.Ready)
            assertEquals("the reader opened another chapter", chapterId, ready.currentChapter?.chapter?.id)
            assertEquals("the page list is not the folder's", PAGE_COLORS.size, ready.totalPages)

            val chapter = checkNotNull(ready.currentChapter)
            val loader = chapter.pageLoader
            assertTrue("the chapter was not loaded from its download: $loader", loader is DownloadedPageLoader)

            val pages = checkNotNull(chapter.pages) { "the current chapter published no page list" }
            pages.forEachIndexed { index, page ->
                val stream = checkNotNull(page.stream) { "page ${index + 1} has no stream" }
                val bytes = stream().use { it.readBytes() }
                val file = pageFiles[index]
                assertArrayEquals("page ${index + 1} is not ${file.name}", file.readBytes(), bytes)
            }

            val first = pages.first()
            awaitTrue(PAGE_TIMEOUT_MILLIS, "the viewer never loaded page 1") {
                first.route != null || first.status is PageState.Error
            }
            assertTrue("page 1 did not decode: ${first.status}", first.status is PageState.Ready)
            logcat(LOG_TAG, LogPriority.INFO) {
                "downloaded: ${ready.totalPages} pages from ${chapterDir.name}, page 1 route ${first.route}"
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun aManifestRowWhoseFolderIsGoneFallsThroughToTheMissingSource() {
        assertTrue("could not remove the chapter folder", chapterDir.deleteRecursively())

        assertTheReaderClosesSaying<SourceNotInstalledException>(R.string.error_source_not_installed)
    }

    /**
     * The folder is still there and holds no page, so the chapter is still a download and its page
     * list is empty. Upstream's chapter loader closes the reader with `page_list_empty_error` here, and
     * so does this one, through the failure's own key (ruling R67 rule 3), not an English message.
     */
    @Test
    fun aDownloadedChapterWhoseFolderHoldsNoPageClosesTheReaderSayingSo() {
        pageFiles.forEach { assertTrue("could not remove ${it.name}", it.delete()) }
        assertTrue("the chapter folder went with its pages", chapterDir.isDirectory)

        assertTheReaderClosesSaying<PageListEmptyException>(R.string.error_page_list_empty)
    }

    /**
     * Upstream's rule with 3 chapters ahead, for an entry in the library (R151): from chapter 2, the next
     * unread chapters are 2 to 5, the first 3 of them are 2, 3 and 4, and 2 is downloaded already, so
     * 3 and 4 are queued and 5 is not.
     */
    @Test
    fun aPagePastAQuarterOfADownloadedChapterQueuesTheNextChaptersWhenTheNextOneIsDownloaded() {
        val chapters = addChaptersAhead(downloadChapterTwo = true)

        turnToPageTwoOfChapterOne()

        val expected = setOf(chapters.getValue(3).id, chapters.getValue(4).id)
        awaitTrue(QUEUE_TIMEOUT_MILLIS, "the reader queued nothing ahead; queue: ${queuedChapterIds()}") {
            queuedChapterIds().isNotEmpty()
        }
        // A second page report must not add a chapter either, so the queue is read again later.
        Thread.sleep(SETTLE_MILLIS)
        assertEquals("the reader queued other chapters than upstream's rule", expected, queuedChapterIds())
        logcat(LOG_TAG, LogPriority.INFO) { "download ahead: queued ${queuedChapterIds().size} chapters" }
    }

    /**
     * R151 drops upstream's guard that the next chapter be downloaded already: a library entry whose next
     * chapter is online queues the next 3 unread, 2, 3 and 4.
     */
    @Test
    fun aPagePastAQuarterQueuesTheNextChaptersWhenTheNextOneIsNotDownloaded() {
        val chapters = addChaptersAhead(downloadChapterTwo = false)

        turnToPageTwoOfChapterOne()

        val expected = setOf(chapters.getValue(2).id, chapters.getValue(3).id, chapters.getValue(4).id)
        awaitTrue(QUEUE_TIMEOUT_MILLIS, "the reader queued nothing ahead; queue: ${queuedChapterIds()}") {
            queuedChapterIds().isNotEmpty()
        }
        Thread.sleep(SETTLE_MILLIS)
        assertEquals("the reader queued other chapters than the next 3 unread", expected, queuedChapterIds())
    }

    /** R151's library gate: an entry that is not in the library downloads nothing ahead. */
    @Test
    fun aPagePastAQuarterQueuesNothingForAnEntryNotInTheLibrary() {
        addChaptersAhead(downloadChapterTwo = true, inLibrary = false)

        turnToPageTwoOfChapterOne()

        Thread.sleep(SETTLE_MILLIS)
        assertEquals("the reader queued chapters ahead for an entry not in the library", emptySet<Long>(), queuedChapterIds())
    }

    @Test
    fun aDownloadedChapterReadToItsLastPageIsDeletedWhenTheReaderIsLeft() {
        setDeleteSettings(slot = LAST_READ_CHAPTER, excluded = emptySet())

        readToTheLastPageAndLeave { assertDownloaded("the chapter was deleted while the reader was open") }

        awaitTrue(DELETE_TIMEOUT_MILLIS, "the read chapter's download is still there after the reader closed") {
            !chapterDir.exists() && manifestRow() == null
        }
        logcat(LOG_TAG, LogPriority.INFO) { "delete after reading: ${chapterDir.name} deleted when the reader closed" }
    }

    @Test
    fun aDownloadedChapterOfAnEntryInAnExcludedCategoryIsKeptWhenTheReaderIsLeft() {
        // The entry is in no category, which upstream's guard counts as the default category, 0.
        setDeleteSettings(slot = LAST_READ_CHAPTER, excluded = setOf(DEFAULT_CATEGORY))

        readToTheLastPageAndLeave { }

        Thread.sleep(SETTLE_MILLIS)
        assertDownloaded("the chapter of an entry in an excluded category was deleted")
        logcat(LOG_TAG, LogPriority.INFO) { "delete after reading: ${chapterDir.name} kept, category excluded" }
    }

    @Test
    fun markingADownloadedChapterReadDeletesItWithAfterManuallyMarkedAsReadOn() {
        setDeleteSettings(slot = DISABLED, excluded = emptySet(), afterMarkedAsRead = true)
        val chapter = runBlocking { checkNotNull(graph.chapterRepository.getChapterById(chapterId)) }
        // The download is there before the mark, so its absence after is the mark's doing.
        assertDownloaded("the seed left no download for the mark read to delete")

        val result = runBlocking { graph.setReadStatus.await(true, chapter) }

        assertTrue("the mark read wrote nothing: $result", result is SetReadStatus.Result.Success)
        awaitTrue(DELETE_TIMEOUT_MILLIS, "the chapter marked read kept its download") {
            !chapterDir.exists() && manifestRow() == null
        }
        logcat(LOG_TAG, LogPriority.INFO) { "delete after a mark read: ${chapterDir.name} deleted" }
    }

    /**
     * Sets the four delete keys, bookmarked chapters kept, and queues each old value to be put back.
     */
    private fun setDeleteSettings(slot: Int, excluded: Set<String>, afterMarkedAsRead: Boolean = false) {
        val preferences = graph.downloadPreferences
        val oldSlot = preferences.removeAfterReadSlots.get()
        val oldMarkedAsRead = preferences.removeAfterMarkedAsRead.get()
        val oldBookmarked = preferences.removeBookmarkedChapters.get()
        val oldExcluded = preferences.removeExcludeCategories.get()
        restoreSettings += {
            preferences.removeAfterReadSlots.set(oldSlot)
            preferences.removeAfterMarkedAsRead.set(oldMarkedAsRead)
            preferences.removeBookmarkedChapters.set(oldBookmarked)
            preferences.removeExcludeCategories.set(oldExcluded)
        }
        preferences.removeAfterReadSlots.set(slot)
        preferences.removeAfterMarkedAsRead.set(afterMarkedAsRead)
        preferences.removeBookmarkedChapters.set(false)
        preferences.removeExcludeCategories.set(excluded)
    }

    /**
     * Opens chapter 1 from its download, selects its last page, waits for the row to say read, runs
     * [whileOpen], then presses back, which finishes the reader as the owner's back does, and waits
     * for it to be destroyed.
     */
    private fun readToTheLastPageAndLeave(whileOpen: () -> Unit) {
        val (scenario, viewModel) = launchReader(ReaderIntents.newIntent(context, mangaId, chapterId, page = 0))
        try {
            val ready = await(OPEN_TIMEOUT_MILLIS, "the session never became ready") {
                viewModel.state.first { it.phase is ReaderPhase.Ready && it.totalPages > 0 || failed(it) }
            }
            assertTrue("the reader failed: ${ready.phase}", ready.phase is ReaderPhase.Ready)
            val chapter = checkNotNull(ready.currentChapter)
            assertTrue("chapter 1 was not read from its download", chapter.pageLoader is DownloadedPageLoader)
            val pages = checkNotNull(chapter.pages) { "chapter 1 published no page list" }
            instrumentation.runOnMainSync { viewModel.send(ReaderEvent.PageSelected(pages.last())) }
            awaitTrue(DELETE_TIMEOUT_MILLIS, "the last page never marked the chapter read") {
                runBlocking { graph.chapterRepository.getChapterById(chapterId)?.read == true }
            }
            whileOpen()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitTrue(CLOSE_TIMEOUT_MILLIS, "back did not close the reader") {
                scenario.state == Lifecycle.State.DESTROYED
            }
        } finally {
            scenario.close()
        }
    }

    private fun manifestRow(): DownloadManifestEntry? = runBlocking {
        graph.downloadManifestRepository.getByChapterId(chapterId)
    }

    private fun assertDownloaded(message: String) {
        assertTrue("$message: the folder is gone", pageFiles.all { it.isFile })
        assertNotNull("$message: the manifest row is gone", manifestRow())
    }

    /**
     * Chapters 2 to 5 after the seeded chapter 1, ordered by source position the way a source lists
     * them, newest first. Chapter 2 is recorded as a download with its pages when asked. Download
     * ahead is set to 3 chapters. Answers the chapters by number.
     */
    private fun addChaptersAhead(downloadChapterTwo: Boolean, inLibrary: Boolean = true): Map<Int, Chapter> = runBlocking {
        aheadSetting = graph.downloadPreferences.autoDownloadWhileReading.get()
        // Download ahead runs for library entries only (R151); the seeded entry is not one until here.
        if (inLibrary) graph.mangaRepository.update(MangaUpdate(id = mangaId, favorite = true))
        graph.downloadPreferences.autoDownloadWhileReading.set(AHEAD_CHAPTERS)
        graph.chapterRepository.update(ChapterUpdate(id = chapterId, sourceOrder = sourceOrderOf(1)))
        val added = graph.chapterRepository.addAll(
            (2..5).map { number ->
                Chapter.create().copy(
                    mangaId = mangaId,
                    url = "/fixture/$mangaTitle/$number",
                    name = "Chapter $number",
                    chapterNumber = number.toDouble(),
                    sourceOrder = sourceOrderOf(number),
                )
            },
        ).associateBy { it.chapterNumber.toInt() }
        if (downloadChapterTwo) {
            val two = added.getValue(2)
            val dir = File(graph.downloadRoot.mangaDir(SOURCE_DIR_NAME, mangaTitle), two.name)
            assertTrue("could not create chapter 2's folder", dir.mkdirs())
            val files = PAGE_COLORS.mapIndexed { index, color ->
                File(dir, "%03d.png".format(index + 1)).also { writePage(it, color) }
            }
            graph.downloadManifestRepository.recordDownload(
                DownloadManifestEntry(
                    id = 0,
                    chapterId = two.id,
                    mangaId = mangaId,
                    sourceId = SOURCE_ID,
                    mangaTitle = mangaTitle,
                    mangaUrl = "/fixture/$mangaTitle",
                    chapterUrl = two.url,
                    chapterName = two.name,
                    scanlator = null,
                    chapterDirName = two.name,
                    pageCount = files.size.toLong(),
                    sizeBytes = files.sumOf { it.length() },
                    dateDownloaded = System.currentTimeMillis(),
                ),
            )
            aheadChapterIds += two.id
        }
        assertEquals("the queue held this entry's chapters before the test", emptySet<Long>(), queuedChapterIds())
        added
    }

    /** Opens chapter 1 from its download and selects its second page of three, past a quarter. */
    private fun turnToPageTwoOfChapterOne() {
        val (scenario, viewModel) = launchReader(ReaderIntents.newIntent(context, mangaId, chapterId, page = 0))
        try {
            val ready = await(OPEN_TIMEOUT_MILLIS, "the session never became ready") {
                viewModel.state.first { it.phase is ReaderPhase.Ready && it.totalPages > 0 || failed(it) }
            }
            assertTrue("the reader failed: ${ready.phase}", ready.phase is ReaderPhase.Ready)
            val chapter = checkNotNull(ready.currentChapter)
            assertTrue("chapter 1 was not read from its download", chapter.pageLoader is DownloadedPageLoader)
            val pages = checkNotNull(chapter.pages) { "chapter 1 published no page list" }
            instrumentation.runOnMainSync { viewModel.send(ReaderEvent.PageSelected(pages[1])) }
            await(OPEN_TIMEOUT_MILLIS, "the reader never moved to page 2") {
                viewModel.state.first { it.currentPage == 1 }
            }
            // The report runs on the reader's scope, so the reader stays open while it lands.
            Thread.sleep(SETTLE_MILLIS)
        } finally {
            scenario.close()
        }
    }

    private fun queuedChapterIds(): Set<Long> = runBlocking {
        graph.downloadQueueRepository.getByMangaId(mangaId).mapTo(mutableSetOf()) { it.chapterId }
    }

    /** Chapter 1 is the oldest, so it has the highest source position. */
    private fun sourceOrderOf(number: Int): Long = (5 - number).toLong()

    /**
     * Opens the chapter and asserts it fails with [T] anywhere in its causes, then that the reader
     * closes itself with a toast whose text is [why], read from the accessibility event the
     * platform sends for it, before anything here closes it.
     */
    private inline fun <reified T : Throwable> assertTheReaderClosesSaying(@StringRes why: Int) {
        val toasts = CopyOnWriteArrayList<String>()
        val automation = instrumentation.uiAutomation
        automation.setOnAccessibilityEventListener { event ->
            if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                event.className?.toString()?.startsWith(Toast::class.java.name) == true
            ) {
                // The platform's event can carry the app's label as a further item beside the
                // message (seen on API 36), so each item is kept apart rather than joined.
                event.text.mapTo(toasts) { it.toString() }
            }
        }
        try {
            val (scenario, viewModel) = launchReader(ReaderIntents.newIntent(context, mangaId, chapterId, page = 0))
            try {
                val settled = await(OPEN_TIMEOUT_MILLIS, "the session neither failed nor became ready") {
                    viewModel.state.first { failed(it) || it.phase is ReaderPhase.Ready }
                }
                val phase = settled.phase
                assertTrue("a chapter with no pages opened: $phase", phase is ReaderPhase.Failed)
                val causes = generateSequence((phase as ReaderPhase.Failed).error) { it.cause }.toList()
                assertNotNull("the reader failed for another reason: $causes", causes.firstOrNull { it is T })

                // Nothing here closes the reader before these hold: it closes itself, saying why.
                awaitTrue(CLOSE_TIMEOUT_MILLIS, "the reader failed and stayed open") {
                    scenario.state == Lifecycle.State.DESTROYED
                }
                val text = context.getString(why)
                awaitTrue(CLOSE_TIMEOUT_MILLIS, "the reader closed without saying \"$text\"; toasts seen: $toasts") {
                    text in toasts
                }
            } finally {
                scenario.close()
            }
        } finally {
            automation.setOnAccessibilityEventListener(null)
        }
    }

    private fun failed(state: ReaderState) = state.phase is ReaderPhase.Failed

    /**
     * Launches the reader and takes the activity's own view model when the activity is resumed (see
     * [ReaderSmokeTest] for why the provider builds nothing). A chapter that fails at once closes the
     * reader as designed, and `ActivityScenario.launch` returns when the activity has settled, which
     * can be after it was destroyed, so `scenario.onActivity` then threw "Activity has been destroyed
     * already" (2 failures in 13 runs on the API 26 AVD, unit K2's report). The lifecycle callback
     * runs on the main thread inside the activity's own `onResume`, before anything can finish it,
     * and the view model keeps its last state after the activity is gone.
     */
    @Suppress("DEPRECATION")
    private fun launchReader(intent: Intent): Pair<ActivityScenario<ReaderActivity>, ReaderViewModel> {
        val captured = AtomicReference<ReaderViewModel>()
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity is ReaderActivity && stage == Stage.RESUMED && captured.get() == null) {
                captured.set(ViewModelProvider(activity)[ReaderViewModel::class.java])
            }
        }
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        monitor.addLifecycleCallback(callback)
        val scenario = try {
            ActivityScenario.launch<ReaderActivity>(intent)
        } finally {
            monitor.removeLifecycleCallback(callback)
        }
        val viewModel = captured.get()
        if (viewModel == null) {
            scenario.close()
            throw AssertionError("the reader was never resumed")
        }
        return scenario to viewModel
    }

    private fun <T> await(timeoutMillis: Long, message: String, block: suspend () -> T): T = runBlocking {
        try {
            withTimeout(timeoutMillis) { block() }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("$message (after $timeoutMillis ms)", e)
        }
    }

    private fun awaitTrue(timeoutMillis: Long, message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (!condition()) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("$message (after $timeoutMillis ms)")
            Thread.sleep(POLL_MILLIS)
        }
    }

    /** A real PNG in its own colour, so each page's bytes differ from every other page's. */
    private fun writePage(target: File, color: Int) {
        val bitmap = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val LOG_TAG = "SaikouDownloadedReader"

        /** No extension carries this id, and the assertion in the setup checks this device agrees. */
        const val SOURCE_ID = 7_310_000_000_000_001L

        /** The folder a source of that id would have had; the locator walks every source folder. */
        const val SOURCE_DIR_NAME = "Fixture Missing Source"

        const val FIXTURE_PREFIX = "saikou-downloaded-"
        const val CHAPTER_NAME = "Chapter 1"
        const val CHAPTER_DIR_NAME = "Chapter 1"

        val PAGE_COLORS = listOf(0xFF402020.toInt(), 0xFF204020.toInt(), 0xFF202040.toInt())
        const val PAGE_WIDTH = 64
        const val PAGE_HEIGHT = 96

        const val OPEN_TIMEOUT_MILLIS = 30_000L
        const val PAGE_TIMEOUT_MILLIS = 15_000L
        const val CLOSE_TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 50L

        const val AHEAD_CHAPTERS = 3
        const val QUEUE_TIMEOUT_MILLIS = 10_000L
        const val SETTLE_MILLIS = 2_000L

        /** `remove_after_read_slots`: off, and the chapter just finished. */
        const val DISABLED = -1
        const val LAST_READ_CHAPTER = 0
        const val DEFAULT_CATEGORY = "0"
        const val DELETE_TIMEOUT_MILLIS = 10_000L
    }
}
