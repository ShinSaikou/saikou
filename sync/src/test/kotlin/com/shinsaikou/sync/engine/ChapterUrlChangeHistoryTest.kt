package com.shinsaikou.sync.engine

import com.shinsaikou.data.DatabaseTransactionRunner
import com.shinsaikou.data.download.DownloadManifestRepositoryImpl
import com.shinsaikou.domain.chapter.interactor.GetExcludedScanlators
import com.shinsaikou.domain.chapter.interactor.SyncChaptersWithSource
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
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.interactor.FetchInterval
import java.util.Date

/**
 * A source that moves its chapters to new urls. The refresh deletes each old row and inserts the
 * new one with the old row's progress, and the history row on the old chapter went with the delete
 * (`history.chapter_id` cascades), so the entry dropped out of History and its reading time was lost.
 * The history now moves to the new row, over the real schema, cascade and triggers.
 */
class ChapterUrlChangeHistoryTest {

    private val a = SyncTestDevice(InMemorySyncBackend())

    @AfterEach
    fun close() = a.close()

    private fun SyncTestDevice.refresh(url: String) = runBlocking {
        val chapters = ChapterRepositoryImpl(handler)
        val getChaptersByMangaId = GetChaptersByMangaId(chapters)
        val mangas = MangaRepositoryImpl(handler)
        SyncChaptersWithSource(
            chapterRepository = chapters,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = UpdateManga(mangas, FetchInterval(getChaptersByMangaId)),
            getChaptersByMangaId = getChaptersByMangaId,
            transactionRunner = DatabaseTransactionRunner(handler),
            downloadManifestRepository = DownloadManifestRepositoryImpl(handler),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            getExcludedScanlators = GetExcludedScanlators(mangas),
            historyRepository = HistoryRepositoryImpl(handler),
        ).await(listOf(ChapterInfo(url = url, name = NAME, dateUpload = DATE)), mangas.getMangaById(ENTRY))
    }

    /** The reader's history write for the chapter at [url]: read at [READ_AT] for [DURATION]. */
    private fun SyncTestDevice.read(url: String) = runBlocking {
        HistoryRepositoryImpl(handler).upsertHistory(
            HistoryUpdate(checkNotNull(chapterId(ENTRY, url)), Date(READ_AT), DURATION),
        )
    }

    /** Every history row with its chapter's url: url, last read, time read. */
    private fun SyncTestDevice.history(): List<List<String?>> = rows(
        "SELECT C.url, H.last_read, H.time_read FROM history H JOIN chapters C ON C._id = H.chapter_id ORDER BY C.url",
        3,
    )

    @Test
    fun `a chapter the source moved to a new url keeps its reading history`() {
        a.addFavourite(ENTRY)
        a.refresh(OLD)
        a.read(OLD)
        val seq = a.latestSeq()

        a.refresh(NEW)

        a.chapterId(ENTRY, OLD) shouldBe null
        a.history() shouldBe listOf(listOf(NEW, "$READ_AT", "$DURATION"))
        // Logged as the new chapter's history, so a sync carries it to the other device.
        val logged = a.rows(
            "SELECT entity_id, change_type FROM sync_change_log WHERE seq > $seq AND entity_type = 'history'",
            2,
        )
        logged shouldBe listOf(listOf("${a.chapterId(ENTRY, NEW)}", "upsert"))
    }

    private companion object {
        const val ENTRY = 1L
        const val OLD = "/series/1/chapter/1"
        const val NEW = "/series/1/chapter/1-v2"
        const val NAME = "Chapter 1"
        const val DATE = 3_000L
        const val READ_AT = 50_000L
        const val DURATION = 60_000L
    }
}
