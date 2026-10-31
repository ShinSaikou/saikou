package com.shinsaikou.sync.engine

import com.shinsaikou.data.DatabaseTransactionRunner
import com.shinsaikou.data.download.DownloadManifestRepositoryImpl
import com.shinsaikou.domain.chapter.interactor.GetExcludedScanlators
import com.shinsaikou.domain.chapter.interactor.SyncChaptersWithSource
import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.sync.InMemorySyncBackend
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
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
import tachiyomi.domain.manga.interactor.FetchInterval

/**
 * A downloaded chapter the source stops listing is soft deleted, and restored under its old id when
 * the source lists it again (`SyncChaptersWithSource`). The relisting is one edit, so the restore and
 * the source's current values are one UPDATE (R113 item 5 and carry-over 1, phase 4 unit CR): the
 * chapter's version moves once and the change log gets one row, whether or not the values changed,
 * as the soft delete moved it once. The merge takes a chapter's source values from the strictly
 * higher version, so the count decides which device's values a sync keeps. Both devices run the real
 * interactor over their own repositories, schema and triggers.
 *
 * The earlier shape ran a separate `restore` UPDATE first. Its bump survived only when nothing else
 * changed: the coalescing update after it wrote back the version read before the restore, so a
 * relisting with a change moved the version once but logged twice, and one with no change moved it
 * not at all.
 */
class SyncRelistedChapterTest {

    private val backend = InMemorySyncBackend()
    private val a = SyncTestDevice(backend)
    private val b = SyncTestDevice(backend)

    @AfterEach
    fun close() {
        a.close()
        b.close()
    }

    /** A refresh of the entry with [infos] as its chapter list, through the interactor the app's update runs. */
    private fun SyncTestDevice.refresh(vararg infos: ChapterInfo) = runBlocking {
        val chapters = ChapterRepositoryImpl(handler)
        val getChaptersByMangaId = GetChaptersByMangaId(chapters)
        val mangas = MangaRepositoryImpl(handler)
        val sync = SyncChaptersWithSource(
            chapterRepository = chapters,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = UpdateManga(mangas, FetchInterval(getChaptersByMangaId)),
            getChaptersByMangaId = getChaptersByMangaId,
            transactionRunner = DatabaseTransactionRunner(handler),
            downloadManifestRepository = DownloadManifestRepositoryImpl(handler),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            getExcludedScanlators = GetExcludedScanlators(mangas),
            historyRepository = HistoryRepositoryImpl(handler),
        )
        sync.await(infos.toList(), mangas.getMangaById(entryId()))
    }

    private fun SyncTestDevice.entryId(): Long = checkNotNull(mangaId(SyncTestDevice.SOURCE, SERIES))

    private fun SyncTestDevice.relisted(column: String): Long? =
        long("SELECT $column FROM chapters WHERE url = '$RELISTED'")

    /** A manifest row for the relisted chapter, which makes a refresh that misses it soft delete it. */
    private fun SyncTestDevice.download() = exec(
        """
        INSERT INTO downloads_manifest(chapter_id, manga_id, source_id, manga_title, manga_url, chapter_url,
            chapter_name, scanlator, chapter_dir_name, page_count, size_bytes, date_downloaded)
        VALUES (${checkNotNull(chapterId(entryId(), RELISTED))}, ${entryId()}, ${SyncTestDevice.SOURCE}, 'Title 1',
            '$SERIES', '$RELISTED', '$NAME', NULL, '$NAME', 1, 0, 1000)
        """,
    )

    /** The chapter's changes in the log after [seq], oldest first. */
    private fun SyncTestDevice.changesAfter(seq: Long): List<String?> = rows(
        """
        SELECT change_type FROM sync_change_log
        WHERE seq > $seq AND entity_type = 'chapter' AND entity_id = ${checkNotNull(chapterId(entryId(), RELISTED))}
        ORDER BY seq
        """,
        1,
    ).map { it.single() }

    @Test
    fun `a relisted chapter the source renamed moves its version once for the relisting, so the other device's edits tie with it`() {
        a.addFavourite(1L)
        a.refresh(ChapterInfo(url = RELISTED, name = NAME, dateUpload = DATE), OTHER)
        a.download()
        a.sync().shouldNotBeNull().exported shouldBe true
        b.sync().shouldNotBeNull().exportsApplied shouldBe 1
        val version = checkNotNull(a.relisted("version"))
        b.relisted("version") shouldBe version
        val seqA = a.latestSeq()
        val seqB = b.latestSeq()

        // Before either syncs, two library updates on each device, each seeing one change to the
        // chapter. A's source drops it, and its download soft deletes it; then the source lists it
        // again under a new name.
        a.refresh(OTHER)
        val tombstone = a.relisted("deleted_at").shouldNotBeNull()
        a.relisted("version") shouldBe version + 1
        a.refresh(ChapterInfo(url = RELISTED, name = RENAMED, dateUpload = DATE), OTHER)
        // B's source credits a group, then re-dates the chapter.
        b.refresh(ChapterInfo(url = RELISTED, name = NAME, scanlator = GROUP, dateUpload = DATE), OTHER)
        b.refresh(ChapterInfo(url = RELISTED, name = NAME, scanlator = GROUP, dateUpload = REDATED), OTHER)

        // Restored under its old id, with the new name, the tombstone cleared and the restore's
        // clock stamped past it, all by the one UPDATE.
        a.rows("SELECT name, deleted_at FROM chapters WHERE url = '$RELISTED'", 2) shouldBe
            listOf(listOf(RENAMED, null))
        a.relisted("restored_at").shouldNotBeNull() shouldBeGreaterThan tombstone
        a.relisted("version") shouldBe version + 2
        b.relisted("version") shouldBe version + 2
        a.changesAfter(seqA) shouldBe listOf("delete", "upsert")
        b.changesAfter(seqB) shouldBe listOf("upsert", "upsert")

        // Equal versions: the tie-break keeps the values whose encoding sorts higher, the same on
        // both devices, B's here (its name is the longer). A second bump on A for the one relisting
        // would have taken A's values outright and dropped B's group and date.
        a.sync().shouldNotBeNull()
        b.sync().shouldNotBeNull()
        a.sync().shouldNotBeNull()
        a.library() shouldBe b.library()
        a.rows("SELECT name, scanlator, date_upload, deleted_at FROM chapters WHERE url = '$RELISTED'", 4) shouldBe
            listOf(listOf(NAME, GROUP, "$REDATED", null))
    }

    @Test
    fun `a chapter relisted unchanged moves its version once, as its soft delete did`() {
        a.addFavourite(1L)
        a.refresh(ChapterInfo(url = RELISTED, name = NAME, dateUpload = DATE), OTHER)
        a.download()
        a.sync().shouldNotBeNull().exported shouldBe true
        b.sync().shouldNotBeNull().exportsApplied shouldBe 1
        val version = checkNotNull(a.relisted("version"))
        val seqA = a.latestSeq()

        a.refresh(OTHER)
        val tombstone = a.relisted("deleted_at").shouldNotBeNull()
        a.relisted("version") shouldBe version + 1
        a.refresh(ChapterInfo(url = RELISTED, name = NAME, dateUpload = DATE), OTHER)

        a.rows("SELECT name, deleted_at FROM chapters WHERE url = '$RELISTED'", 2) shouldBe listOf(listOf(NAME, null))
        a.relisted("restored_at").shouldNotBeNull() shouldBeGreaterThan tombstone
        a.relisted("version") shouldBe version + 2
        a.changesAfter(seqA) shouldBe listOf("delete", "upsert")

        // B never saw the chapter go: it takes the restore and the version with it.
        a.sync().shouldNotBeNull()
        b.sync().shouldNotBeNull()
        b.relisted("version") shouldBe version + 2
        b.relisted("deleted_at") shouldBe null
        a.library() shouldBe b.library()
    }

    private companion object {
        const val SERIES = "/series/1"
        const val RELISTED = "/series/1/chapter/1"
        const val NAME = "Chapter 1"
        const val RENAMED = "Ch.1"
        const val GROUP = "Group"
        const val DATE = 3_000L
        const val REDATED = 4_000L
        val OTHER = ChapterInfo(url = "/series/1/chapter/2", name = "Chapter 2", dateUpload = DATE)
    }
}
