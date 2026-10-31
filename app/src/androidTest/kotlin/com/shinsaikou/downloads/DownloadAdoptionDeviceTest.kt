package com.shinsaikou.downloads

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import com.shinsaikou.SaikouApp
import com.shinsaikou.di.AppGraph
import com.shinsaikou.downloads.adoption.AdoptionWorker
import com.shinsaikou.reader.ReaderActivity
import com.shinsaikou.reader.ReaderIntents
import com.shinsaikou.reader.ReaderViewModel
import com.shinsaikou.reader.pipeline.DownloadedPageLoader
import com.shinsaikou.reader.pipeline.PageState
import com.shinsaikou.reader.state.ReaderPhase
import eu.kanade.tachiyomi.data.download.DownloadProvider
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
import tachiyomi.domain.manga.model.Manga
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The adoption of upstream's downloads on a device (phase 2 unit J), end to end through the app's own
 * graph: a small upstream tree is generated as plain files, the adoption is started through
 * [com.shinsaikou.downloads.adoption.AdoptionScheduler], the scheduler the Downloads settings row calls,
 * with the tree as a `file:` URI, which the worker opens as `UniFile` does any `file:` URI, and
 * WorkManager runs [AdoptionWorker] for real. Only the system tree picker is left out: it is platform
 * UI, and what it hands back is a URI the same worker opens the same way.
 *
 * The tree holds one chapter as a folder and one as a `.cbz`, of an entry whose source is not
 * installed, matched through the name remembered for its id, beside a folder that matches nothing.
 * After the run both chapters are opened in the reader with the device in airplane mode. The source
 * id is one no extension carries, so every branch of the reader's resolver but the downloaded one
 * ends in a missing source; a session that reaches Ready from a [DownloadedPageLoader], with every
 * page's bytes the bytes written for it, read those pages from the adopted copy. The `.cbz` is the
 * first real archive a device opens through that branch (unit H's carry-over 7).
 *
 * Every name here is synthetic; `CONTRIBUTING.md` binds test data too.
 */
@RunWith(AndroidJUnit4::class)
class DownloadAdoptionDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as SaikouApp
    private val graph: AppGraph get() = app.graph

    private var mangaId: Long = -1L
    private lateinit var mangaTitle: String
    private lateinit var folderChapter: Chapter
    private lateinit var archiveChapter: Chapter
    private lateinit var tree: File
    private lateinit var folderPages: List<ByteArray>
    private lateinit var archivePages: List<ByteArray>
    private lateinit var folderName: String
    private lateinit var archiveName: String
    private lateinit var mangaDirName: String

    @Before
    fun generateAnUpstreamTree() {
        assertNull("the fixture source id is installed on this device", graph.sourceManager.get(SOURCE_ID))
        assertTrue("an adoption is already pending", graph.storagePreferences.adoptionTree.get().isEmpty())

        mangaTitle = "$FIXTURE_PREFIX${System.nanoTime()}"
        runBlocking {
            graph.stubSourceRepository.upsertStubSource(SOURCE_ID, SOURCE_LANG, SOURCE_NAME)
            val manga = graph.mangaRepository.insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = SOURCE_ID,
                        url = "/fixture/$mangaTitle",
                        title = mangaTitle,
                        favorite = true,
                    ),
                ),
            ).single()
            mangaId = manga.id
            val chapters = graph.chapterRepository.addAll(
                listOf(1.0, 2.0).map { number ->
                    Chapter.create().copy(
                        mangaId = mangaId,
                        url = "/fixture/$mangaTitle/${number.toInt()}",
                        name = "Chapter ${number.toInt()}",
                        chapterNumber = number,
                    )
                },
            )
            folderChapter = chapters.single { it.chapterNumber == 1.0 }
            archiveChapter = chapters.single { it.chapterNumber == 2.0 }
        }

        val ascii = graph.libraryPreferences.disallowNonAsciiFilenames.get()
        folderName = DownloadProvider.getChapterDirName(folderChapter.name, null, folderChapter.url, ascii)
        archiveName = DownloadProvider.getChapterDirName(archiveChapter.name, null, archiveChapter.url, ascii) + ".cbz"

        tree = File(context.filesDir, "adoption-fixture-${System.nanoTime()}")
        mangaDirName = DownloadProvider.getMangaDirName(mangaTitle, ascii)
        val mangaDir = File(tree, "downloads/$UPSTREAM_SOURCE_DIR/$mangaDirName")
        folderPages = FOLDER_COLORS.map(::png)
        File(mangaDir, folderName).apply {
            assertTrue("could not create the chapter folder", mkdirs())
            folderPages.forEachIndexed { index, bytes -> File(this, "%03d.png".format(index + 1)).writeBytes(bytes) }
        }
        archivePages = ARCHIVE_COLORS.map(::png)
        ZipOutputStream(File(mangaDir, archiveName).outputStream()).use { zip ->
            archivePages.forEachIndexed { index, bytes ->
                zip.putNextEntry(ZipEntry("%03d.png".format(index + 1)))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        File(mangaDir, UNMATCHED_NAME).apply {
            mkdirs()
            File(this, "001.png").writeBytes(png(FOLDER_COLORS.first()))
        }
    }

    @After
    fun removeTheFixture() {
        setAirplaneMode(false)
        runBlocking {
            val ids = listOfNotNull(
                if (::folderChapter.isInitialized) folderChapter.id else null,
                if (::archiveChapter.isInitialized) archiveChapter.id else null,
            )
            if (ids.isNotEmpty()) graph.downloadManifestRepository.remove(ids)
            if (mangaId > 0) graph.mangaRepository.softDelete(mangaId, System.currentTimeMillis())
        }
        graph.storagePreferences.adoptionTree.delete()
        graph.downloadRoot.sourceDir(SOURCE_NAME).deleteRecursively()
        if (::tree.isInitialized) tree.deleteRecursively()
    }

    @Test
    fun aFolderAndACbzAreAdoptedAndBothOpenInTheReaderOffline() {
        val before = snapshot(tree)

        runBlocking {
            assertTrue("the adoption did not start", graph.adoptionScheduler.start(Uri.fromFile(tree).toString()))
            val finished = await(ADOPTION_TIMEOUT_MILLIS, "the adoption never finished") {
                graph.workManager.getWorkInfosForUniqueWorkFlow(AdoptionWorker.WORK_NAME)
                    .first { infos -> infos.isNotEmpty() && infos.all { it.state.isFinished } }
            }
            assertEquals(WorkInfo.State.SUCCEEDED, finished.single().state)
        }

        val summary = checkNotNull(graph.adoptionSummaryStore.summary.value) { "the run stored no summary" }
        logcat(LOG_TAG, LogPriority.INFO) { "adoption summary: $summary" }
        assertEquals("failures: ${summary.failures}", 0, summary.failures.size)
        assertEquals(2, summary.matched)
        assertEquals(2, summary.copied)
        assertEquals(
            listOf("downloads/$UPSTREAM_SOURCE_DIR/$mangaDirName/$UNMATCHED_NAME"),
            summary.unmatched.map { it.path },
        )
        assertTrue("the adoption key was not cleared", graph.storagePreferences.adoptionTree.get().isEmpty())
        assertEquals("the picked tree changed", before, snapshot(tree))

        val folderEntry = runBlocking { graph.downloadManifestRepository.getByChapterId(folderChapter.id) }
        val archiveEntry = runBlocking { graph.downloadManifestRepository.getByChapterId(archiveChapter.id) }
        assertEquals(folderName, checkNotNull(folderEntry).chapterDirName)
        assertEquals(archiveName, checkNotNull(archiveEntry).chapterDirName)
        assertEquals(ARCHIVE_COLORS.size.toLong(), archiveEntry.pageCount)
        assertTrue(
            "the archive was not copied as a file",
            runBlocking { graph.downloadedChapters.find(archiveChapter.id) }?.isFile == true,
        )

        setAirplaneMode(true)
        openAndCheck(folderChapter, folderPages)
        openAndCheck(archiveChapter, archivePages)
    }

    /** Opens [chapter] and checks every page came from its adopted copy, in order, and page one decoded. */
    private fun openAndCheck(chapter: Chapter, expected: List<ByteArray>) {
        val scenario = ActivityScenario.launch<ReaderActivity>(ReaderIntents.newIntent(context, mangaId, chapter.id))
        try {
            val viewModel = viewModelOf(scenario)
            val ready = await(OPEN_TIMEOUT_MILLIS, "the session never became ready") {
                viewModel.state.first {
                    it.phase is ReaderPhase.Ready && it.totalPages > 0 || it.phase is ReaderPhase.Failed
                }
            }
            assertTrue("the reader failed on ${chapter.name}: ${ready.phase}", ready.phase is ReaderPhase.Ready)
            assertEquals(chapter.id, ready.currentChapter?.chapter?.id)
            assertEquals(expected.size, ready.totalPages)

            val current = checkNotNull(ready.currentChapter)
            assertTrue(
                "${chapter.name} was not loaded from its download: ${current.pageLoader}",
                current.pageLoader is DownloadedPageLoader,
            )
            val pages = checkNotNull(current.pages) { "the current chapter published no page list" }
            pages.forEachIndexed { index, page ->
                val stream = checkNotNull(page.stream) { "page ${index + 1} has no stream" }
                assertArrayEquals("page ${index + 1} of ${chapter.name}", expected[index], stream().use { it.readBytes() })
            }
            val first = pages.first()
            awaitTrue(PAGE_TIMEOUT_MILLIS, "the viewer never loaded page 1 of ${chapter.name}") {
                first.route != null || first.status is PageState.Error
            }
            assertTrue("page 1 of ${chapter.name} did not decode: ${first.status}", first.status is PageState.Ready)
            assertNotNull(first.route)
            logcat(LOG_TAG, LogPriority.INFO) {
                "adopted ${chapter.name}: ${ready.totalPages} pages through ${current.pageLoader?.javaClass?.simpleName}"
            }
        } finally {
            scenario.close()
        }
    }

    /**
     * Airplane mode through the shell, so the reader has no network at all while it opens the
     * adopted chapters. This AVD belongs to this unit's run; the setting is restored in [After].
     */
    private fun setAirplaneMode(on: Boolean) {
        val command = "cmd connectivity airplane-mode ${if (on) "enable" else "disable"}"
        instrumentation.uiAutomation.executeShellCommand(command).close()
        SystemClock.sleep(AIRPLANE_SETTLE_MILLIS)
    }

    /** The activity's own view model; see `ReaderSmokeTest` for why the provider builds nothing. */
    @Suppress("DEPRECATION")
    private fun viewModelOf(scenario: ActivityScenario<ReaderActivity>): ReaderViewModel {
        lateinit var viewModel: ReaderViewModel
        scenario.onActivity { activity -> viewModel = ViewModelProvider(activity)[ReaderViewModel::class.java] }
        return viewModel
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

    /** Every path under [root] with a digest of its bytes and its modification time. */
    private fun snapshot(root: File): Map<String, String> = root.walkTopDown().associate { file ->
        val digest = if (file.isFile) {
            MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        } else {
            "dir"
        }
        file.relativeTo(root).path to "$digest ${file.lastModified()}"
    }

    /** A real PNG in its own colour, so each page's bytes differ from every other page's. */
    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val LOG_TAG = "SaikouAdoption"

        /** No extension carries this id, and the assertion in the setup checks this device agrees. */
        const val SOURCE_ID = 7_310_000_000_000_002L
        const val SOURCE_NAME = "Fixture Adopted"
        const val SOURCE_LANG = "en"

        /** The folder upstream gives that source: its `HttpSource.toString`. */
        const val UPSTREAM_SOURCE_DIR = "Fixture Adopted (EN)"

        const val FIXTURE_PREFIX = "saikou-adopted-"
        const val UNMATCHED_NAME = "Chapter 9_000000"

        val FOLDER_COLORS = listOf(0xFF402020.toInt(), 0xFF204020.toInt(), 0xFF202040.toInt())
        val ARCHIVE_COLORS = listOf(0xFF404020.toInt(), 0xFF402040.toInt(), 0xFF204040.toInt(), 0xFF303030.toInt())
        const val PAGE_WIDTH = 64
        const val PAGE_HEIGHT = 96

        const val ADOPTION_TIMEOUT_MILLIS = 60_000L
        const val OPEN_TIMEOUT_MILLIS = 30_000L
        const val PAGE_TIMEOUT_MILLIS = 15_000L
        const val POLL_MILLIS = 50L
        const val AIRPLANE_SETTLE_MILLIS = 2_000L
    }
}
