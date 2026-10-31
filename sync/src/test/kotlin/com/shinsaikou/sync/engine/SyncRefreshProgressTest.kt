package com.shinsaikou.sync.engine

import com.shinsaikou.data.DatabaseTransactionRunner
import com.shinsaikou.data.download.DownloadManifestRepositoryImpl
import com.shinsaikou.domain.chapter.interactor.GetExcludedScanlators
import com.shinsaikou.domain.chapter.interactor.SyncChaptersWithSource
import com.shinsaikou.domain.database.TransactionRunner
import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.sync.InMemorySyncBackend
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.interactor.FetchInterval

/**
 * A library refresh sends only the source's fields for a chapter it updates (phase 4 unit BF, R120
 * carry-over 1). `SyncChaptersWithSource` reads the entry's chapters before its transaction, and
 * each update used to carry the read flag, bookmark, last page and version read then: a progress
 * commit the reader made on that chapter in between was written back over, its page and read flag
 * went back, and the version undercounted the commit. The reader's write lands here in that window,
 * through a seam in the transaction runner, over the real schema and triggers.
 */
class SyncRefreshProgressTest {

    private val device = SyncTestDevice(InMemorySyncBackend())

    @AfterEach
    fun close() = device.close()

    /** A refresh listing [infos], with [beforeTransaction] run after the interactor's reads. */
    private fun refresh(vararg infos: ChapterInfo, beforeTransaction: suspend () -> Unit = {}) = runBlocking {
        val chapters = ChapterRepositoryImpl(device.handler)
        val getChaptersByMangaId = GetChaptersByMangaId(chapters)
        val mangas = MangaRepositoryImpl(device.handler)
        val real = DatabaseTransactionRunner(device.handler)
        val seam = object : TransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T {
                beforeTransaction()
                return real.inTransaction(block)
            }
        }
        SyncChaptersWithSource(
            chapterRepository = chapters,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = UpdateManga(mangas, FetchInterval(getChaptersByMangaId)),
            getChaptersByMangaId = getChaptersByMangaId,
            transactionRunner = seam,
            downloadManifestRepository = DownloadManifestRepositoryImpl(device.handler),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            getExcludedScanlators = GetExcludedScanlators(mangas),
            historyRepository = HistoryRepositoryImpl(device.handler),
        ).await(infos.toList(), mangas.getMangaById(ENTRY))
    }

    /** What the reader's `ProgressCommitter` writes for a chapter it finishes on [page]. */
    private fun finishChapter(page: Long) = runBlocking {
        UpdateChapter(ChapterRepositoryImpl(device.handler))
            .await(ChapterUpdate(id = chapterId(), read = true, lastPageRead = page))
    }

    private fun chapterId(): Long = checkNotNull(device.chapterId(ENTRY, URL))

    private fun chapter(): List<String?> =
        device.rows("SELECT name, read, last_page_read, version FROM chapters WHERE url = '$URL'", 4).single()

    private fun changesAfter(seq: Long): List<String?> = device.rows(
        "SELECT change_type FROM sync_change_log WHERE seq > $seq AND entity_type = 'chapter' ORDER BY seq",
        1,
    ).map { it.single() }

    @Test
    fun `a progress commit that lands between the refresh's read and its transaction survives the refresh`() {
        device.addFavourite(ENTRY)
        refresh(ChapterInfo(url = URL, name = NAME, dateUpload = DATE))
        val version = chapter()[3]!!.toLong()
        val seq = device.latestSeq()

        refresh(ChapterInfo(url = URL, name = RENAMED, dateUpload = DATE)) { finishChapter(page = 7L) }

        // The source's rename and the reader's progress, each moving the version once.
        chapter() shouldBe listOf(RENAMED, "1", "7", "${version + 2}")
        changesAfter(seq) shouldBe listOf("upsert", "upsert")
    }

    @Test
    fun `a refresh that changes a source field moves the version once and keeps the progress`() {
        device.addFavourite(ENTRY)
        refresh(ChapterInfo(url = URL, name = NAME, dateUpload = DATE))
        finishChapter(page = 7L)
        val version = chapter()[3]!!.toLong()
        val seq = device.latestSeq()

        refresh(ChapterInfo(url = URL, name = RENAMED, dateUpload = DATE))

        chapter() shouldBe listOf(RENAMED, "1", "7", "${version + 1}")
        changesAfter(seq) shouldBe listOf("upsert")
    }

    private companion object {
        const val ENTRY = 1L
        const val URL = "/series/1/chapter/1"
        const val NAME = "Chapter 1"
        const val RENAMED = "Chapter 1: Renamed"
        const val DATE = 3_000L
    }
}
