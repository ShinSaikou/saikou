package com.shinsaikou.downloads

import com.shinsaikou.domain.download.model.DownloadManifestEntry
import com.shinsaikou.domain.download.model.DownloadQueueEntry
import com.shinsaikou.domain.download.repository.DownloadManifestRepository
import eu.kanade.tachiyomi.data.download.DownloadProvider
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.io.IOException

/**
 * Unit migrate-downloads (fixable warning F1): the old entry's downloaded chapters matched to the
 * new entry's and moved or copied there, against the real schema and files under a temporary
 * directory. The old entry's source is never installed here, the usual reason to migrate, so every
 * test also shows the old folders are found without it.
 */
class MigrationDownloadsTest : DownloadTestBase() {

    private val queue = RecordingQueue()

    /** A chapter whose row write throws, as a disk that refuses the write would. */
    private var refuseRowOf: Long? = null

    override val manifestRepository: DownloadManifestRepository
        get() = object : DownloadManifestRepository by realManifestRepository {
            override suspend fun moveToChapter(fromChapterId: Long, entry: DownloadManifestEntry) {
                if (fromChapterId == refuseRowOf) throw IOException("fixture: row refused")
                realManifestRepository.moveToChapter(fromChapterId, entry)
            }

            override suspend fun recordDownload(entry: DownloadManifestEntry) {
                if (entry.chapterId == refuseRowOf) throw IOException("fixture: row refused")
                realManifestRepository.recordDownload(entry)
            }
        }

    @BeforeEach
    fun seed() {
        insertManga(id = OLD, title = "Old title", sourceId = OLD_SOURCE)
        insertManga(id = NEW, title = "New title", sourceId = SOURCE_ID)
    }

    private fun TestScope.migration(): MigrationDownloads {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return MigrationDownloads(
            manifestRepository = manifestRepository,
            queueRepository = queueRepository,
            chapterRepository = chapterRepository,
            downloadQueue = queue,
            downloadManifest = manifest(dispatcher),
            downloadRoot = downloadRoot,
            locator = locator(dispatcher),
            writeLock = writeLock,
            sourceManager = sourceManager,
            libraryPreferences = libraryPreferences,
            dispatchers = dispatchers(dispatcher),
        )
    }

    /**
     * A download of old chapter [chapterId] in the folder an install of the old source wrote, with
     * its manifest row; the folder holds two pages whose bytes name the chapter.
     */
    private suspend fun downloadOld(chapterId: Long): File {
        val chapter = chapter(chapterId)
        val dirName = DownloadProvider.getChapterDirName(chapter.name, chapter.scanlator, chapter.url, false)
        realManifestRepository.recordDownload(manifestEntry(chapter, manga(OLD), dirName))
        return File(File(File(downloadRoot.root(), OLD_SOURCE_DIR), "Old title"), dirName).apply {
            mkdirs()
            File(this, "001.png").writeText("page 1 of $chapterId")
            File(this, "002.png").writeText("page 2 of $chapterId")
        }
    }

    /**
     * A download of old chapter [chapterId] saved as an archive, as the engine does with the CBZ
     * switch on: `<chapter folder name>.cbz` in the old entry's folder, and a row naming it.
     */
    private suspend fun downloadOldArchive(chapterId: Long): File {
        val chapter = chapter(chapterId)
        val dirName = DownloadProvider.getChapterDirName(chapter.name, chapter.scanlator, chapter.url, false) + ".cbz"
        realManifestRepository.recordDownload(manifestEntry(chapter, manga(OLD), dirName))
        return File(File(File(downloadRoot.root(), OLD_SOURCE_DIR), "Old title"), dirName).apply {
            parentFile.mkdirs()
            writeText("archive of $chapterId")
        }
    }

    /** The folder new chapter [chapterId] downloads to. */
    private suspend fun newFolder(chapterId: Long): File = File(mangaDir(NEW), chapterDirName(chapterId))

    private fun File.pages(): List<String> = listFiles().orEmpty().sortedBy { it.name }.map { it.readText() }

    private suspend fun old(): Manga = manga(OLD)

    private suspend fun new(): Manga = manga(NEW)

    @Test
    fun `Migrate renames the folder to the new chapter's and repoints its row at the new chapter, entry, source and title`() =
        runTest {
            insertChapter(id = 10, mangaId = OLD, number = 1.0)
            insertChapter(id = 20, mangaId = NEW, name = "Episode 1", url = "/new/1", number = 1.0)
            val oldFolder = downloadOld(10)

            val outcome = migration().transfer(old(), new(), move = true)

            outcome shouldBe MigrationDownloads.Outcome(transferred = listOf(20L), leftBehind = emptyList())
            oldFolder.exists() shouldBe false
            // The old manga and source folders went with their last chapter.
            File(downloadRoot.root(), OLD_SOURCE_DIR).exists() shouldBe false
            newFolder(20).pages() shouldContainExactly listOf("page 1 of 10", "page 2 of 10")
            realManifestRepository.getByChapterId(10).shouldBeNull()
            val row = realManifestRepository.getByChapterId(20).shouldNotBeNull()
            row.mangaId shouldBe NEW
            row.sourceId shouldBe SOURCE_ID
            row.mangaTitle shouldBe "New title"
            row.mangaUrl shouldBe "/manga/$NEW"
            row.chapterUrl shouldBe "/new/1"
            row.chapterName shouldBe "Episode 1"
            row.chapterDirName shouldBe chapterDirName(20)
            row.pageCount shouldBe PAGES.toLong()
            manifest(StandardTestDispatcher(testScheduler)).getChapterDir(20) shouldBe newFolder(20)
            queue.removedManga shouldContainExactly listOf(OLD)
        }

    @Test
    fun `Copy copies the files to the new chapter's folder and the old entry keeps its own row and files`() = runTest {
        insertChapter(id = 10, mangaId = OLD, number = 1.0)
        insertChapter(id = 20, mangaId = NEW, url = "/new/1", number = 1.0)
        val oldFolder = downloadOld(10)

        val outcome = migration().transfer(old(), new(), move = false)

        outcome shouldBe MigrationDownloads.Outcome(transferred = listOf(20L), leftBehind = emptyList())
        oldFolder.pages() shouldContainExactly listOf("page 1 of 10", "page 2 of 10")
        newFolder(20).pages() shouldContainExactly listOf("page 1 of 10", "page 2 of 10")
        File(mangaDir(NEW), chapterDirName(20) + DownloadProvider.TMP_DIR_SUFFIX).exists() shouldBe false
        realManifestRepository.getByChapterId(10).shouldNotBeNull().mangaId shouldBe OLD
        realManifestRepository.getByChapterId(20).shouldNotBeNull().mangaId shouldBe NEW
        queue.removedManga.shouldBeEmpty()
    }

    @Test
    fun `Copy copies a chapter saved as an archive to the new chapter's archive name and the old one keeps its own`() =
        runTest {
            insertChapter(id = 10, mangaId = OLD, number = 1.0)
            insertChapter(id = 20, mangaId = NEW, url = "/new/1", number = 1.0)
            val oldArchive = downloadOldArchive(10)
            val newArchive = File(mangaDir(NEW), chapterDirName(20) + ".cbz")

            val outcome = migration().transfer(old(), new(), move = false)

            outcome shouldBe MigrationDownloads.Outcome(transferred = listOf(20L), leftBehind = emptyList())
            oldArchive.readText() shouldBe "archive of 10"
            newArchive.readText() shouldBe "archive of 10"
            File(mangaDir(NEW), newArchive.name + DownloadProvider.TMP_DIR_SUFFIX).exists() shouldBe false
            realManifestRepository.getByChapterId(20).shouldNotBeNull().chapterDirName shouldBe newArchive.name
            realManifestRepository.getByChapterId(10).shouldNotBeNull().chapterDirName shouldBe oldArchive.name
            manifest(StandardTestDispatcher(testScheduler)).getChapterDir(20) shouldBe newArchive
        }

    @Test
    fun `Migrate renames a chapter saved as an archive to the new chapter's archive name and repoints its row`() =
        runTest {
            insertChapter(id = 10, mangaId = OLD, number = 1.0)
            insertChapter(id = 20, mangaId = NEW, url = "/new/1", number = 1.0)
            val oldArchive = downloadOldArchive(10)
            val newArchive = File(mangaDir(NEW), chapterDirName(20) + ".cbz")

            val outcome = migration().transfer(old(), new(), move = true)

            outcome shouldBe MigrationDownloads.Outcome(transferred = listOf(20L), leftBehind = emptyList())
            oldArchive.exists() shouldBe false
            newArchive.readText() shouldBe "archive of 10"
            realManifestRepository.getByChapterId(10).shouldBeNull()
            realManifestRepository.getByChapterId(20).shouldNotBeNull().chapterDirName shouldBe newArchive.name
        }

    @Test
    fun `a chapter with no match and one with no recognised number are left behind, and Delete them deletes their files and rows`() =
        runTest {
            insertChapter(id = 10, mangaId = OLD, number = 1.0)
            insertChapter(id = 11, mangaId = OLD, number = 7.0)
            insertChapter(id = 12, mangaId = OLD, name = "Extra", number = -1.0)
            insertChapter(id = 20, mangaId = NEW, url = "/new/1", number = 1.0)
            insertChapter(id = 21, mangaId = NEW, url = "/new/extra", name = "Extra", number = -1.0)
            downloadOld(10)
            val seven = downloadOld(11)
            val extra = downloadOld(12)
            val migration = migration()

            val outcome = migration.transfer(old(), new(), move = true)

            outcome.transferred shouldContainExactly listOf(20L)
            outcome.leftBehind shouldContainExactlyInAnyOrder listOf(11L, 12L)
            seven.exists() shouldBe true
            extra.exists() shouldBe true
            realManifestRepository.getByMangaId(OLD).map { it.chapterId } shouldContainExactlyInAnyOrder listOf(11L, 12L)

            migration.delete(outcome.leftBehind)

            seven.exists() shouldBe false
            extra.exists() shouldBe false
            realManifestRepository.getByMangaId(OLD).shouldBeEmpty()
            realManifestRepository.getByChapterId(20).shouldNotBeNull()
        }

    @Test
    fun `a new chapter already downloaded is not a match, and its download is kept`() = runTest {
        insertChapter(id = 10, mangaId = OLD, number = 1.0)
        insertChapter(id = 20, mangaId = NEW, url = "/new/1", number = 1.0)
        val oldFolder = downloadOld(10)
        realManifestRepository.recordDownload(manifestEntry(chapter(20), new(), chapterDirName(20)))
        newFolder(20).apply { mkdirs() }.resolve("001.png").writeText("the new entry's own page")

        val outcome = migration().transfer(old(), new(), move = true)

        outcome shouldBe MigrationDownloads.Outcome(transferred = emptyList(), leftBehind = listOf(10L))
        oldFolder.exists() shouldBe true
        newFolder(20).pages() shouldContainExactly listOf("the new entry's own page")
    }

    @Test
    fun `a row whose files are gone is not a download and is neither moved nor counted`() = runTest {
        insertChapter(id = 10, mangaId = OLD, number = 1.0)
        insertChapter(id = 11, mangaId = OLD, number = 2.0)
        insertChapter(id = 20, mangaId = NEW, url = "/new/1", number = 1.0)
        insertChapter(id = 21, mangaId = NEW, url = "/new/2", number = 2.0)
        downloadOld(10)
        downloadOld(11).deleteRecursively()

        val outcome = migration().transfer(old(), new(), move = true)

        outcome shouldBe MigrationDownloads.Outcome(transferred = listOf(20L), leftBehind = emptyList())
        realManifestRepository.getByChapterId(21).shouldBeNull()
    }

    @Test
    fun `the downloaded chapters a Migrate that carries nothing reports are the rows whose files are there, of that entry only`() =
        runTest {
            insertChapter(id = 10, mangaId = OLD, number = 1.0)
            insertChapter(id = 11, mangaId = OLD, number = 2.0)
            insertChapter(id = 12, mangaId = OLD, number = 3.0)
            insertChapter(id = 20, mangaId = NEW, url = "/new/1", number = 1.0)
            downloadOld(10)
            downloadOld(11).deleteRecursively()
            downloadOld(12)
            realManifestRepository.recordDownload(manifestEntry(chapter(20), new(), chapterDirName(20)))
            newFolder(20).mkdirs()

            val migration = migration()

            migration.downloadedChapters(OLD) shouldContainExactlyInAnyOrder listOf(10L, 12L)
            migration.downloadedChapters(NEW) shouldContainExactly listOf(20L)
            // Read only: the row whose files are gone stays for the sweep, and nothing moved.
            realManifestRepository.getByChapterId(11).shouldNotBeNull()
            queue.removedManga.shouldBeEmpty()
        }

    @Test
    fun `an entry with no downloads has no downloaded chapters`() = runTest {
        insertChapter(id = 10, mangaId = OLD, number = 1.0)

        migration().downloadedChapters(OLD).shouldBeEmpty()
    }

    @Test
    fun `a move that fails mid-batch leaves that chapter where it was with its row unchanged, and the rest go on`() = runTest {
        insertChapter(id = 10, mangaId = OLD, number = 1.0)
        insertChapter(id = 11, mangaId = OLD, number = 2.0)
        insertChapter(id = 12, mangaId = OLD, number = 3.0)
        insertChapter(id = 20, mangaId = NEW, url = "/new/1", number = 1.0)
        insertChapter(id = 21, mangaId = NEW, url = "/new/2", number = 2.0)
        insertChapter(id = 22, mangaId = NEW, url = "/new/3", number = 3.0)
        downloadOld(10)
        val second = downloadOld(11)
        downloadOld(12)
        val before = realManifestRepository.getByChapterId(11)
        refuseRowOf = 11

        val outcome = migration().transfer(old(), new(), move = true)

        outcome.transferred shouldContainExactlyInAnyOrder listOf(20L, 22L)
        outcome.leftBehind shouldContainExactly listOf(11L)
        // The rename was put back when the row write failed.
        second.pages() shouldContainExactly listOf("page 1 of 11", "page 2 of 11")
        newFolder(21).exists() shouldBe false
        realManifestRepository.getByChapterId(11) shouldBe before
        realManifestRepository.getByChapterId(21).shouldBeNull()
        newFolder(20).pages() shouldContainExactly listOf("page 1 of 10", "page 2 of 10")
        newFolder(22).pages() shouldContainExactly listOf("page 1 of 12", "page 2 of 12")
    }

    @Test
    fun `a new chapter's folder another download's row names is refused, and that chapter stays`() = runTest {
        insertChapter(id = 10, mangaId = OLD, number = 1.0)
        insertChapter(id = 20, mangaId = NEW, url = "/new/1", number = 1.0)
        // A third entry of the target's source with the target's title: folders are keyed by source
        // and title, so its chapter with the same url holds the very folder chapter 20 would take.
        insertManga(id = 3, title = "New title", sourceId = SOURCE_ID)
        insertChapter(id = 30, mangaId = 3, url = "/new/1", number = 1.0)
        realManifestRepository.recordDownload(manifestEntry(chapter(30), manga(3), chapterDirName(20)))
        val oldFolder = downloadOld(10)

        val outcome = migration().transfer(old(), new(), move = true)

        outcome shouldBe MigrationDownloads.Outcome(transferred = emptyList(), leftBehind = listOf(10L))
        oldFolder.exists() shouldBe true
        realManifestRepository.getByChapterId(10).shouldNotBeNull()
    }

    @Test
    fun `a matched new chapter still queued leaves the queue once it has the files`() = runTest {
        insertChapter(id = 10, mangaId = OLD, number = 1.0)
        insertChapter(id = 20, mangaId = NEW, url = "/new/1", number = 1.0)
        insertChapter(id = 21, mangaId = NEW, url = "/new/2", number = 2.0)
        queueRepository.enqueue(chapterId = 20, mangaId = NEW, now = 1)
        queueRepository.enqueue(chapterId = 21, mangaId = NEW, now = 1)
        downloadOld(10)

        migration().transfer(old(), new(), move = true)

        queue.removed shouldContainExactly listOf(listOf(20L))
    }

    @Test
    fun `an old entry with no downloads reads nothing else and touches nothing`() = runTest {
        insertChapter(id = 10, mangaId = OLD, number = 1.0)
        insertChapter(id = 20, mangaId = NEW, url = "/new/1", number = 1.0)

        val outcome = migration().transfer(old(), new(), move = false)

        outcome shouldBe MigrationDownloads.Outcome.NONE
        File(downloadRoot.root(), source.name).exists() shouldBe false
    }

    @Test
    fun `the old entry's folders are deleted without its source installed`() = runTest {
        insertChapter(id = 10, mangaId = OLD, number = 1.0)
        val folder = downloadOld(10)
        sourceManager.get(OLD_SOURCE).shouldBeNull()

        manifest(StandardTestDispatcher(testScheduler)).deleteManga(OLD)

        folder.exists() shouldBe false
        realManifestRepository.getByMangaId(OLD).shouldBeEmpty()
    }

    @Test
    fun `matching takes the same recognised number only`() {
        val matches = MigrationDownloads.match(
            downloaded = listOf(old(10, 1.0), old(11, 2.5), old(12, -1.0)),
            candidates = listOf(new(20, 2.5, order = 0), new(21, 1.0, order = 1), new(22, -1.0, order = 2)),
            taken = emptySet(),
        )

        matches.mapValues { it.value.id } shouldBe mapOf(10L to 21L, 11L to 20L)
    }

    @Test
    fun `with several candidates, the same scanlator wins, then the first in the source's order`() {
        val matches = MigrationDownloads.match(
            downloaded = listOf(old(10, 5.0, scanlator = "Group B", order = 0), old(11, 5.0, scanlator = "Group A", order = 1)),
            candidates = listOf(
                new(22, 5.0, scanlator = "Group C", order = 2),
                new(20, 5.0, scanlator = "Group A", order = 0),
                new(21, 5.0, scanlator = "Group D", order = 1),
            ),
            taken = emptySet(),
        )

        // Group A's download takes Group A's chapter although Group B's comes first; Group B's takes
        // the first left in the source's order.
        matches.mapValues { it.value.id } shouldBe mapOf(11L to 20L, 10L to 21L)
    }

    @Test
    fun `no scanlator matches a blank one, and a chapter is given once`() {
        val matches = MigrationDownloads.match(
            downloaded = listOf(old(10, 3.0, scanlator = null, order = 0), old(11, 3.0, scanlator = "Group A", order = 1)),
            candidates = listOf(new(20, 3.0, scanlator = "Group A", order = 0), new(21, 3.0, scanlator = " ", order = 1)),
            taken = emptySet(),
        )

        matches.mapValues { it.value.id } shouldBe mapOf(10L to 21L, 11L to 20L)
    }

    @Test
    fun `a candidate already downloaded is never a match`() {
        val matches = MigrationDownloads.match(
            downloaded = listOf(old(10, 1.0)),
            candidates = listOf(new(20, 1.0)),
            taken = setOf(20L),
        )

        matches.shouldBeEmpty()
    }

    private fun old(id: Long, number: Double, scanlator: String? = null, order: Long = 0) =
        Chapter.create().copy(id = id, mangaId = OLD, chapterNumber = number, scanlator = scanlator, sourceOrder = order)

    private fun new(id: Long, number: Double, scanlator: String? = null, order: Long = 0) =
        Chapter.create().copy(id = id, mangaId = NEW, chapterNumber = number, scanlator = scanlator, sourceOrder = order)

    /** The queue's controls as calls; the rows themselves are the real repository's. */
    private class RecordingQueue : DownloadQueue {
        val removed = mutableListOf<List<Long>>()
        val removedManga = mutableListOf<Long>()

        override fun subscribe(): Flow<List<DownloadQueueEntry>> = emptyFlow()
        override val isRunning: StateFlow<Boolean> = MutableStateFlow(false)
        override val isPaused: StateFlow<Boolean> = MutableStateFlow(false)
        override suspend fun enqueue(manga: Manga, chapters: List<Chapter>, autoStart: Boolean) = Unit
        override suspend fun remove(chapterIds: List<Long>) {
            removed += chapterIds
        }
        override suspend fun removeManga(mangaId: Long) {
            removedManga += mangaId
        }
        override suspend fun reorder(orderedChapterIds: List<Long>) = Unit
        override suspend fun start() = Unit
        override suspend fun pause() = Unit
        override suspend fun clear() = Unit
    }

    private companion object {
        const val OLD = 1L
        const val NEW = 2L

        /** Not installed: [DownloadTestBase.FakeSourceManager] knows only [SOURCE_ID]. */
        const val OLD_SOURCE = 99L
        const val OLD_SOURCE_DIR = "Old Source"
    }
}
