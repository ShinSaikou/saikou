package com.shinsaikou.downloads

import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.source.SourceIds
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.manga.interactor.GetManga
import java.io.File

/**
 * Upstream's delete after reading and after a manual mark read (phase 3 unit DR) over the engine's
 * fixture: the real repositories on the JVM driver, the real manifest and queue over their tables,
 * and the files under a temporary directory. What was deleted is read back from the disk and the
 * manifest table, and every call to [DownloadManifest.delete] is recorded.
 *
 * Entry 1 is a favourite in no category with chapters 1 to 6, each downloaded with one page. The
 * reader's session list reaches [ReadChapterCleanup.chapterFinished] as ids, nearest first, the way
 * `ReaderSession.readBack` builds it; that list's own rules are `:reader`'s `ReaderSessionTest`.
 *
 * Every name here is synthetic; `CONTRIBUTING.md` binds test data too.
 */
class ReadChapterCleanupRulesTest : DownloadTestBase() {

    private val scheduler = mockk<DownloadScheduler>(relaxed = true) {
        every { isRunning } returns flowOf(false)
    }

    /** The app's preference file as far as the staging store sees it: one map, read by every store. */
    private val staged = mutableMapOf<String, Any>()
    private val stagingPreferences = object : PreferenceStore by InMemoryPreferenceStore() {
        override fun getAll(): Map<String, *> = staged.toMap()
    }

    /** Every id [DownloadManifest.delete] was called with, in order. */
    private val deleteCalls = mutableListOf<List<Long>>()

    @BeforeEach
    fun seed() {
        insertManga(id = 1)
        (1L..6L).forEach { insertChapter(id = it, mangaId = 1) }
    }

    /** A new store over the same map is a new process over the same preference file. */
    private fun store() = PendingChapterDeleteStore(stagingPreferences) { key, value ->
        if (value == null) staged.remove(key) else staged[key] = value
    }

    private fun TestScope.recordingManifest(): DownloadManifest {
        val real = manifest(StandardTestDispatcher(testScheduler))
        return object : DownloadManifest by real {
            override suspend fun delete(chapterIds: List<Long>) {
                deleteCalls += chapterIds
                real.delete(chapterIds)
            }
        }
    }

    private fun TestScope.queue(manifest: DownloadManifest): WorkManagerDownloadQueue {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return WorkManagerDownloadQueue(
            queueRepository = queueRepository,
            manifest = manifest,
            chapterRepository = chapterRepository,
            getManga = GetManga(mangaRepository),
            locator = locator(dispatcher),
            downloadRoot = downloadRoot,
            engine = engine(dispatcher),
            scheduler = scheduler,
            dispatchers = dispatchers(dispatcher),
            appScope = backgroundScope,
        )
    }

    private fun TestScope.rules(store: PendingChapterDeleteStore = store()): ReadChapterCleanupRules {
        val manifest = recordingManifest()
        return ReadChapterCleanupRules(
            chapterRepository = chapterRepository,
            getManga = GetManga(mangaRepository),
            getCategories = GetCategories(categoryRepository),
            manifest = manifest,
            queue = queue(manifest),
            downloadPreferences = downloadPreferences,
            libraryPreferences = libraryPreferences,
            store = store,
            dispatchers = dispatchers(StandardTestDispatcher(testScheduler)),
            appScope = this,
        )
    }

    /** A manifest row and a folder holding one page, as the engine leaves a downloaded chapter. */
    private suspend fun download(chapterId: Long): File {
        val chapter = chapter(chapterId)
        realManifestRepository.recordDownload(manifestEntry(chapter, manga(chapter.mangaId), chapterDirName(chapterId)))
        val dir = finalDir(chapterId)
        dir.mkdirs()
        File(dir, "001.png").writeBytes(byteArrayOf(1, 2, 3))
        return dir
    }

    private suspend fun downloadAll(): Map<Long, File> = (1L..6L).associateWith { download(it) }

    private fun read(vararg ids: Long) = exec("UPDATE chapters SET read = 1 WHERE _id IN (${ids.joinToString()})")

    private fun unread(vararg ids: Long) = exec("UPDATE chapters SET read = 0 WHERE _id IN (${ids.joinToString()})")

    private fun bookmark(vararg ids: Long) = exec("UPDATE chapters SET bookmark = 1 WHERE _id IN (${ids.joinToString()})")

    private fun category(id: Long) = exec("INSERT INTO categories(_id, name, sort, flags) VALUES ($id, 'Category $id', $id, 0)")

    private fun inCategory(mangaId: Long, categoryId: Long) =
        exec("INSERT INTO mangas_categories(manga_id, category_id) VALUES ($mangaId, $categoryId)")

    private suspend fun downloadedIds(): Set<Long> =
        (1L..6L).filterTo(mutableSetOf()) { realManifestRepository.getByChapterId(it) != null }

    // region after reading: the slot

    @Test
    fun `slot 0 deletes the chapter just finished, and only when the reader closes`() = runTest {
        val dirs = downloadAll()
        downloadPreferences.removeAfterReadSlots.set(0)
        read(3)
        val rules = rules()

        rules.chapterFinished(listOf(3L, 2L, 1L))
        advanceUntilIdle()
        dirs.getValue(3).exists() shouldBe true
        store().pending() shouldBe mapOf(3L to 1L)

        rules.readerClosed()
        advanceUntilIdle()

        dirs.getValue(3).exists() shouldBe false
        downloadedIds() shouldBe setOf(1L, 2L, 4L, 5L, 6L)
        store().pending() shouldBe emptyMap()
    }

    /**
     * Triage E103, upstream `36ec043a5`: with "mark duplicate chapters read: existing" on, the reader
     * marks another group's chapter 3 read with the one it finished, and that copy's download goes
     * too. Chapter 9, the same number again, is bookmarked and keeps its download through the guard.
     */
    @Test
    fun `the duplicates marked read with the finished chapter lose their downloads with it`() = runTest {
        val dirs = downloadAll()
        insertChapter(id = 7, mangaId = 1, name = "Chapter 3", scanlator = "Group B", number = 3.0)
        insertChapter(id = 8, mangaId = 1, name = "Chapter 3", scanlator = "Group C", number = 3.0)
        insertChapter(id = 9, mangaId = 1, name = "Chapter 3", scanlator = "Group D", number = 3.0)
        val duplicates = listOf(7L, 8L, 9L).associateWith { download(it) }
        libraryPreferences.markDuplicateReadChapterAsRead.set(setOf(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING))
        downloadPreferences.removeAfterReadSlots.set(0)
        bookmark(9)
        // What the reader writes: the finished chapter, then its duplicates; 8 was left unread.
        read(3, 7, 9)
        val rules = rules()

        rules.chapterFinished(listOf(3L, 2L, 1L))
        rules.readerClosed()
        advanceUntilIdle()

        dirs.getValue(3).exists() shouldBe false
        duplicates.getValue(7).exists() shouldBe false
        realManifestRepository.getByChapterId(7) shouldBe null
        duplicates.getValue(8).exists() shouldBe true
        duplicates.getValue(9).exists() shouldBe true
        downloadedIds() shouldBe setOf(1L, 2L, 4L, 5L, 6L)
    }

    /** The guard keeps the bookmarked chapter alone; its duplicates pass or fail it on their own. */
    @Test
    fun `a bookmarked finished chapter keeps its download, its duplicates lose theirs`() = runTest {
        val dirs = downloadAll()
        insertChapter(id = 7, mangaId = 1, name = "Chapter 3", scanlator = "Group B", number = 3.0)
        val duplicate = download(7)
        libraryPreferences.markDuplicateReadChapterAsRead.set(setOf(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING))
        downloadPreferences.removeAfterReadSlots.set(0)
        bookmark(3)
        read(3, 7)
        val rules = rules()

        rules.chapterFinished(listOf(3L, 2L, 1L))
        rules.readerClosed()
        advanceUntilIdle()

        dirs.getValue(3).exists() shouldBe true
        duplicate.exists() shouldBe false
        deleteCalls shouldContainExactly listOf(listOf(7L))
    }

    @Test
    fun `without mark duplicates read, a read duplicate keeps its download`() = runTest {
        downloadAll()
        insertChapter(id = 7, mangaId = 1, name = "Chapter 3", scanlator = "Group B", number = 3.0)
        val duplicate = download(7)
        downloadPreferences.removeAfterReadSlots.set(0)
        read(3, 7)
        val rules = rules()

        rules.chapterFinished(listOf(3L, 2L, 1L))
        rules.readerClosed()
        advanceUntilIdle()

        duplicate.exists() shouldBe true
        downloadedIds() shouldBe setOf(1L, 2L, 4L, 5L, 6L)
    }

    @Test
    fun `second to last deletes the chapter before the finished one`() = runTest {
        downloadAll()
        downloadPreferences.removeAfterReadSlots.set(1)
        read(2, 3)
        val rules = rules()

        rules.chapterFinished(listOf(3L, 2L, 1L))
        rules.readerClosed()
        advanceUntilIdle()

        downloadedIds() shouldBe setOf(1L, 3L, 4L, 5L, 6L)
    }

    /**
     * The session list skip read and skip duplicates left: 6, 4 and 2, the reader having skipped 5
     * and 3. "Second to last" is 4, the session's previous chapter, not 5, the chapter before 6 in
     * the entry.
     */
    @Test
    fun `the slot counts the session's chapters, not the entry's, when chapters were skipped`() = runTest {
        downloadAll()
        downloadPreferences.removeAfterReadSlots.set(1)
        read(2, 3, 4, 5, 6)
        val rules = rules()

        rules.chapterFinished(listOf(6L, 4L, 2L))
        rules.readerClosed()
        advanceUntilIdle()

        deleteCalls shouldContainExactly listOf(listOf(4L))
        downloadedIds() shouldBe setOf(1L, 2L, 3L, 5L, 6L)
    }

    /**
     * Challenge M2's session: opened on 5 with skip read on, 1 to 4 read, so the session is 5 and 6.
     * Finishing 5 finds nothing at "second to last"; finishing 6 finds 5, now read, and it goes at
     * the close. A list rebuilt at the finish would have dropped 5 and deleted nothing.
     */
    @Test
    fun `a skip read session deletes the chapter it finished first once it finishes the next`() = runTest {
        downloadAll()
        downloadPreferences.removeAfterReadSlots.set(1)
        read(1, 2, 3, 4)
        val rules = rules()

        read(5)
        rules.chapterFinished(listOf(5L))
        read(6)
        rules.chapterFinished(listOf(6L, 5L))
        rules.readerClosed()
        advanceUntilIdle()

        deleteCalls shouldContainExactly listOf(listOf(5L))
        downloadedIds() shouldBe setOf(1L, 2L, 3L, 4L, 6L)
    }

    @Test
    fun `a slot past the start of the session deletes nothing`() = runTest {
        downloadAll()
        downloadPreferences.removeAfterReadSlots.set(4)
        read(1, 2)
        val rules = rules()

        rules.chapterFinished(listOf(2L, 1L))
        rules.readerClosed()
        advanceUntilIdle()

        store().pending() shouldBe emptyMap()
        deleteCalls.shouldBeEmpty()
    }

    @Test
    fun `disabled, upstream's default, deletes nothing`() = runTest {
        downloadAll()
        read(3)
        val rules = rules()

        rules.chapterFinished(listOf(3L))
        rules.readerClosed()
        advanceUntilIdle()

        downloadPreferences.removeAfterReadSlots.get() shouldBe -1
        deleteCalls.shouldBeEmpty()
        downloadedIds() shouldBe (1L..6L).toSet()
    }

    @Test
    fun `a chosen chapter that is not read is not staged`() = runTest {
        downloadAll()
        downloadPreferences.removeAfterReadSlots.set(1)
        read(3)
        val rules = rules()

        rules.chapterFinished(listOf(3L, 2L))

        store().pending() shouldBe emptyMap()
    }

    // endregion

    // region staging and the close

    @Test
    fun `a staged chapter marked unread before the close keeps its files`() = runTest {
        val dirs = downloadAll()
        downloadPreferences.removeAfterReadSlots.set(0)
        read(3)
        val rules = rules()
        rules.chapterFinished(listOf(3L))

        unread(3)
        rules.readerClosed()
        advanceUntilIdle()

        dirs.getValue(3).exists() shouldBe true
        deleteCalls.shouldBeEmpty()
        store().pending() shouldBe emptyMap()
    }

    /** A killed process: the store that staged is gone, and the next reader close carries it out. */
    @Test
    fun `a staged chapter survives a new store and is deleted at the next close`() = runTest {
        val dirs = downloadAll()
        downloadPreferences.removeAfterReadSlots.set(0)
        read(3)
        rules(store()).chapterFinished(listOf(3L))

        rules(store()).readerClosed()
        advanceUntilIdle()

        dirs.getValue(3).exists() shouldBe false
    }

    /** Stricter than upstream, which deletes what it staged: the guard runs again at the close. */
    @Test
    fun `the close runs the guard again, so a chapter bookmarked after staging keeps its files`() = runTest {
        val dirs = downloadAll()
        downloadPreferences.removeAfterReadSlots.set(0)
        read(3)
        val rules = rules()
        rules.chapterFinished(listOf(3L))

        bookmark(3)
        rules.readerClosed()
        advanceUntilIdle()

        dirs.getValue(3).exists() shouldBe true
        deleteCalls.shouldBeEmpty()
    }

    @Test
    fun `the close runs the guard again, so an entry excluded after staging keeps its files`() = runTest {
        val dirs = downloadAll()
        downloadPreferences.removeAfterReadSlots.set(0)
        read(3)
        val rules = rules()
        rules.chapterFinished(listOf(3L))

        downloadPreferences.removeExcludeCategories.set(setOf("0"))
        rules.readerClosed()
        advanceUntilIdle()

        dirs.getValue(3).exists() shouldBe true
        deleteCalls.shouldBeEmpty()
    }

    @Test
    fun `a close with nothing staged deletes nothing`() = runTest {
        downloadAll()
        read(1, 2, 3)

        rules().readerClosed()
        advanceUntilIdle()

        deleteCalls.shouldBeEmpty()
    }

    /** Upstream's `deleteChapters` takes a chapter out of the download queue before its files go. */
    @Test
    fun `a chapter still in the download queue leaves it before its files go`() = runTest {
        downloadAll()
        downloadPreferences.removeAfterReadSlots.set(0)
        read(3)
        queueRepository.enqueue(chapterId = 3, mangaId = 1, now = 0)
        queueRepository.enqueue(chapterId = 4, mangaId = 1, now = 0)
        val rules = rules()

        rules.chapterFinished(listOf(3L))
        rules.readerClosed()
        advanceUntilIdle()

        queueRepository.subscribeAll().first().map { it.chapterId } shouldContainExactly listOf(4L)
        deleteCalls shouldContainExactly listOf(listOf(3L))
    }

    // endregion

    // region the guard

    @Test
    fun `an entry in an excluded category keeps every read chapter`() = runTest {
        downloadAll()
        category(5)
        inCategory(1, 5)
        downloadPreferences.removeExcludeCategories.set(setOf("5"))
        downloadPreferences.removeAfterReadSlots.set(0)
        read(3)
        val rules = rules()

        rules.chapterFinished(listOf(3L))
        rules.readerClosed()
        advanceUntilIdle()

        store().pending() shouldBe emptyMap()
        deleteCalls.shouldBeEmpty()
        downloadedIds() shouldBe (1L..6L).toSet()
    }

    @Test
    fun `an entry in any excluded category is kept, whatever its other categories`() = runTest {
        downloadAll()
        category(5)
        category(6)
        inCategory(1, 5)
        inCategory(1, 6)
        downloadPreferences.removeExcludeCategories.set(setOf("6"))
        downloadPreferences.removeAfterReadSlots.set(0)
        read(3)

        rules().chapterFinished(listOf(3L))

        store().pending() shouldBe emptyMap()
    }

    /** Upstream's `ifEmpty { listOf(0) }`: an entry in no category is in the default category. */
    @Test
    fun `an entry in no category is kept when the default category is excluded`() = runTest {
        downloadAll()
        downloadPreferences.removeExcludeCategories.set(setOf("0"))
        downloadPreferences.removeAfterReadSlots.set(0)
        read(3)
        val rules = rules()

        rules.chapterFinished(listOf(3L))
        rules.readerClosed()
        advanceUntilIdle()

        store().pending() shouldBe emptyMap()
        deleteCalls.shouldBeEmpty()
    }

    @Test
    fun `an entry outside the excluded categories is deleted from`() = runTest {
        downloadAll()
        category(5)
        inCategory(1, 5)
        downloadPreferences.removeExcludeCategories.set(setOf("0"))
        downloadPreferences.removeAfterReadSlots.set(0)
        read(3)
        val rules = rules()

        rules.chapterFinished(listOf(3L))
        rules.readerClosed()
        advanceUntilIdle()

        deleteCalls shouldContainExactly listOf(listOf(3L))
    }

    @Test
    fun `a bookmarked chapter keeps its files while bookmarked chapters may not be deleted`() = runTest {
        val dirs = downloadAll()
        downloadPreferences.removeAfterReadSlots.set(0)
        read(3)
        bookmark(3)
        val rules = rules()

        rules.chapterFinished(listOf(3L))
        rules.readerClosed()
        advanceUntilIdle()

        downloadPreferences.removeBookmarkedChapters.get() shouldBe false
        store().pending() shouldBe emptyMap()
        dirs.getValue(3).exists() shouldBe true
    }

    @Test
    fun `a bookmarked chapter is deleted once bookmarked chapters may be`() = runTest {
        val dirs = downloadAll()
        downloadPreferences.removeBookmarkedChapters.set(true)
        downloadPreferences.removeAfterReadSlots.set(0)
        read(3)
        bookmark(3)
        val rules = rules()

        rules.chapterFinished(listOf(3L))
        rules.readerClosed()
        advanceUntilIdle()

        dirs.getValue(3).exists() shouldBe false
    }

    /**
     * A Local entry's folder is the owner's only copy. Its chapter is never staged, and a staged one
     * (a store written before this rule) never reaches the manifest's delete.
     */
    @Test
    fun `a Local source chapter is never staged and never reaches the delete`() = runTest {
        insertManga(id = 2, title = "Local Title", sourceId = SourceIds.LOCAL)
        insertChapter(id = 20, mangaId = 2)
        exec("UPDATE chapters SET read = 1 WHERE _id = 20")
        downloadPreferences.removeAfterReadSlots.set(0)
        downloadPreferences.removeAfterMarkedAsRead.set(true)
        val rules = rules()

        rules.chapterFinished(listOf(20L))
        store().pending() shouldBe emptyMap()

        store().add(mangaId = 2, chapterId = 20)
        rules.readerClosed()
        rules.markedRead(listOf(20L))
        advanceUntilIdle()

        deleteCalls.shouldBeEmpty()
    }

    // endregion

    // region after manually marked as read

    @Test
    fun `a manual mark read deletes the chapters it wrote, on the application's scope`() = runTest {
        val dirs = downloadAll()
        read(2, 3)

        rules().markedRead(listOf(2L, 3L))
        dirs.getValue(2).exists() shouldBe true
        advanceUntilIdle()

        dirs.getValue(2).exists() shouldBe false
        dirs.getValue(3).exists() shouldBe false
        downloadedIds() shouldBe setOf(1L, 4L, 5L, 6L)
    }

    /** Stricter than upstream, whose mark read path never meets its exclusion (challenge S2). */
    @Test
    fun `a manual mark read keeps the files of an entry in an excluded category`() = runTest {
        downloadAll()
        downloadPreferences.removeExcludeCategories.set(setOf("0"))
        read(2)

        rules().markedRead(listOf(2L))
        advanceUntilIdle()

        deleteCalls.shouldBeEmpty()
    }

    @Test
    fun `a manual mark read keeps a bookmarked chapter and deletes the rest`() = runTest {
        downloadAll()
        read(2, 3)
        bookmark(2)

        rules().markedRead(listOf(2L, 3L))
        advanceUntilIdle()

        deleteCalls shouldContainExactly listOf(listOf(3L))
    }

    @Test
    fun `a chapter marked unread again before the delete runs keeps its files`() = runTest {
        downloadAll()
        read(2)
        val rules = rules()

        rules.markedRead(listOf(2L))
        unread(2)
        advanceUntilIdle()

        deleteCalls.shouldBeEmpty()
    }

    // endregion
}
