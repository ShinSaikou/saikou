package com.shinsaikou.reader.pipeline

import com.hippo.unifile.UniFile
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.reader.cache.ChapterCache
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import tachiyomi.core.archive.ArchiveEntry
import tachiyomi.core.archive.ArchiveReader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.Dispatchers as CoroutineDispatchers

/**
 * The downloaded loader in both forms a download takes on disk: the folder the engine writes, and
 * the archive an adopted upstream chapter can be. What it has to get right is what the owner sees:
 * the images and only the images, in page order, ready at once, each served from its own file, and
 * each one probed when a viewer asks for it.
 *
 * The archive form runs over a mocked [ArchiveReader], because the real one maps the file through
 * libarchive's native code, which the JVM does not have; the device suite's `LocalPageLoadersTest`
 * covers a real archive through the same `ArchivePageLoader`.
 */
class DownloadedPageLoaderTest {

    @TempDir
    lateinit var dir: File

    private val probe = mockk<ImageProbe> {
        every { probe(any(), any()) } returns PageRoute.Region(ImageFormat.PNG, WIDTH, HEIGHT)
    }
    private val splitter = mockk<TallStripSplitter> { every { plan(any(), any()) } returns null }
    private val dispatchers = Dispatchers(
        io = CoroutineDispatchers.IO,
        default = CoroutineDispatchers.IO,
        main = CoroutineDispatchers.IO,
    )

    @Test
    fun `a downloaded folder is read in page order, images only, every page ready at once`() = runBlocking<Unit> {
        // The engine's own names, written out of order, beside the ComicInfo.xml it writes last.
        listOf("010.png", "002.png", "001.png").forEach { name -> File(dir, name).writeBytes(page(name)) }
        File(dir, "ComicInfo.xml").writeText("<ComicInfo/>")

        val loader = DownloadedPageLoader(directory())
        val pages = loader.getPages()

        loader.isLocal shouldBe true
        pages.map { it.index } shouldContainExactly listOf(0, 1, 2)
        pages.map { it.status } shouldContainExactly List(3) { PageState.Ready }
        pages.map { it.read() } shouldHoldBytes listOf("001.png", "002.png", "010.png").map(::page)
    }

    @Test
    fun `a downloaded folder page is probed when a viewer loads it`() = runBlocking<Unit> {
        File(dir, "001.png").writeBytes(page("001.png"))

        val loader = DownloadedPageLoader(directory())
        val page = loader.getPages().single()
        page.route shouldBe null

        loadUntilRouted(loader, page)

        page.route shouldBe PageRoute.Region(ImageFormat.PNG, WIDTH, HEIGHT)
        page.status shouldBe PageState.Ready
    }

    @Test
    fun `a tall JXL page in a folder is served whole rather than split`() = runBlocking<Unit> {
        // The probe is mocked, so the bytes only have to pass the folder listing, which the name does.
        File(dir, "001.jxl").writeBytes(page("001.jxl"))
        every { probe.probe(any(), any()) } returns PageRoute.Region(ImageFormat.JXL, WIDTH, TALL_HEIGHT)
        every { splitter.plan(any(), any()) } returns SplitPlan(3, emptyList())

        val loader = DownloadedPageLoader(directory())
        val page = loader.getPages().single()
        loadUntilRouted(loader, page)

        page.status shouldBe PageState.Ready
        page.partCount shouldBe 1
        verify(exactly = 0) { splitter.split(any(), any()) }
    }

    @Test
    fun `a downloaded archive is read through the archive loader, and recycling closes it`() = runBlocking<Unit> {
        val entries = listOf(
            ArchiveEntry("002.jpg", isFile = true),
            ArchiveEntry("ComicInfo.xml", isFile = true),
            ArchiveEntry("001.jpg", isFile = true),
            ArchiveEntry("extras/", isFile = false),
        )
        val reader = mockk<ArchiveReader>(relaxed = true) {
            every { useEntries<List<ReaderPage>>(any()) } answers {
                firstArg<(Sequence<ArchiveEntry>) -> List<ReaderPage>>().invoke(entries.asSequence())
            }
            every { getInputStream(any()) } answers {
                val name = firstArg<String>()
                ByteArrayInputStream(if (name.endsWith(".xml")) "<ComicInfo/>".toByteArray() else page(name))
            }
        }

        val loader = DownloadedPageLoader(archive(reader))
        val pages = loader.getPages()

        loader.isLocal shouldBe true
        pages.map { it.status } shouldContainExactly List(2) { PageState.Ready }
        pages.map { it.read() } shouldHoldBytes listOf("001.jpg", "002.jpg").map(::page)

        loadUntilRouted(loader, pages.first())
        pages.first().route.shouldBeInstanceOf<PageRoute.Region>()

        loader.recycle()
        loader.isRecycled shouldBe true
        verify(exactly = 1) { reader.close() }
    }

    private fun directory() = DirectoryPageLoader(
        dir = checkNotNull(UniFile.fromFile(dir)),
        chapterId = CHAPTER_ID,
        probe = probe,
        splitter = splitter,
        cache = mockk<ChapterCache>(relaxed = true),
        readerPreferences = ReaderPreferences(InMemoryPreferenceStore()),
        dispatchers = dispatchers,
    )

    private fun archive(reader: ArchiveReader) = ArchivePageLoader(
        reader = reader,
        chapterId = CHAPTER_ID,
        probe = probe,
        splitter = splitter,
        cache = mockk<ChapterCache>(relaxed = true),
        readerPreferences = ReaderPreferences(InMemoryPreferenceStore()),
        dispatchers = dispatchers,
    )

    /** `loadPage` suspends until cancelled, as a viewer's holder does; the route is what it leaves. */
    private suspend fun loadUntilRouted(loader: PageLoader, page: ReaderPage) {
        val job: Job = CoroutineScope(CoroutineDispatchers.IO).launch { loader.loadPage(page) }
        try {
            withTimeout(TIMEOUT_MS) { while (page.route == null) delay(POLL_MS) }
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun ReaderPage.read(): ByteArray = checkNotNull(stream).invoke().use { it.readBytes() }

    /** A PNG signature followed by the file's own name, so every page's bytes say which file they are. */
    private fun page(name: String): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + name.toByteArray()

    private infix fun List<ByteArray>.shouldHoldBytes(expected: List<ByteArray>) {
        map { it.toList() } shouldContainExactly expected.map { it.toList() }
    }

    private companion object {
        const val CHAPTER_ID = 5L
        const val WIDTH = 64
        const val HEIGHT = 96
        const val TALL_HEIGHT = 20_000
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
