package com.shinsaikou.data.repository

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import com.shinsaikou.data.sync.SyncStoreImpl
import com.shinsaikou.domain.manga.interactor.UpdateManga
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.model.MangaUpdate

/**
 * A library add logs every chapter the entry holds (phase 4 unit NB, challenge c5 changes 1 and 7):
 * `log_manga_progress_on_favorite` the chapters with progress, `MangaRepositoryImpl.partialUpdate`'s
 * statement the rest, each once, in one transaction with the flip. Counted from `sync_change_log`
 * over the real schema and triggers; every flip goes through the repository, since a raw UPDATE
 * fires the trigger alone.
 */
class MangaRepositoryLibraryAddLogTest : RepositoryTest() {

    private val mangaRepository by lazy { MangaRepositoryImpl(handler) }
    private val updateManga by lazy {
        UpdateManga(mangaRepository, FetchInterval(GetChaptersByMangaId(ChapterRepositoryImpl(handler))))
    }

    /** Manga 1 outside the library, holding one chapter of each kind the two writers split. */
    @BeforeEach
    fun seed() {
        insertManga(id = 1, favorite = false)
        chapter(id = 10, mangaId = 1)
        chapter(id = 11, mangaId = 1, read = 1)
        chapter(id = 12, mangaId = 1, bookmark = 1)
        chapter(id = 13, mangaId = 1, lastPageRead = 3)
        chapter(id = 14, mangaId = 1, deletedAt = 5_000)
        chapter(id = 15, mangaId = 1, read = 1, deletedAt = 5_000)
        exec("DELETE FROM sync_change_log")
    }

    private fun chapter(id: Long, mangaId: Long, read: Int = 0, bookmark: Int = 0, lastPageRead: Int = 0, deletedAt: Long? = null) = exec(
        """
        INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, last_page_read,
            chapter_number, source_order, date_fetch, date_upload, memo, deleted_at)
        VALUES ($id, $mangaId, '/series/$mangaId/chapter/$id', 'Chapter $id', NULL,
            $read, $bookmark, $lastPageRead, $id, $id, 0, 0, CAST('{}' AS BLOB), ${deletedAt ?: "NULL"})
        """,
    )

    /** The log's rows of [entity] as `id change`, in seq order. */
    private fun logged(entity: String): List<String> = driver.executeQuery(
        identifier = null,
        sql = "SELECT entity_id, change_type FROM sync_change_log WHERE entity_type = '$entity' ORDER BY seq",
        mapper = { cursor: SqlCursor ->
            val rows = mutableListOf<String>()
            while (cursor.next().value) rows += "${cursor.getLong(0)} ${cursor.getString(1)}"
            QueryResult.Value(rows)
        },
        parameters = 0,
    ).value

    private fun logCount(): Long = scalar("SELECT count(*) FROM sync_change_log")!!.toLong()

    @Test
    fun `a library add through awaitUpdateFavorite logs every chapter the entry holds exactly once`() = runTest {
        updateManga.awaitUpdateFavorite(1, true) shouldBe true

        logged("chapter").map { it.substringBefore(' ').toLong() }.sorted() shouldContainExactly listOf(10L, 11, 12, 13, 14, 15)
        logged("manga") shouldContainExactly listOf("1 upsert")
    }

    @Test
    fun `a library add logs a soft-deleted chapter as an unkeyed delete, with progress or without`() = runTest {
        mangaRepository.update(MangaUpdate(id = 1, favorite = true)) shouldBe true

        logged("chapter").filter { it.endsWith(" delete") }.sorted() shouldContainExactly listOf("14 delete", "15 delete")
        scalar("SELECT count(*) FROM sync_change_log WHERE key_source IS NOT NULL OR key_chapter_url IS NOT NULL") shouldBe "0"
    }

    @Test
    fun `a flip of an entry already in the library logs nothing`() = runTest {
        exec("UPDATE mangas SET favorite = 1 WHERE _id = 1")
        exec("DELETE FROM sync_change_log")

        mangaRepository.update(MangaUpdate(id = 1, favorite = true)) shouldBe true

        logCount() shouldBe 0
    }

    @Test
    fun `awaitUpdateFavorite true on an entry already in the library logs no chapter`() = runTest {
        // The category picker's path with a stale favourite: the write moves date_added only.
        exec("UPDATE mangas SET favorite = 1 WHERE _id = 1")
        exec("DELETE FROM sync_change_log")

        updateManga.awaitUpdateFavorite(1, true) shouldBe true

        logged("chapter").shouldBeEmpty()
    }

    @Test
    fun `a flip written by a sync apply logs nothing`() = runTest {
        val store = SyncStoreImpl(handler, driver)

        store.apply {
            val stored = manga(source = 1, url = "https://example.invalid/manga/1").shouldNotBeNull()
            updateManga(stored.copy(favorite = true))
        }

        scalar("SELECT favorite FROM mangas WHERE _id = 1") shouldBe "1"
        logCount() shouldBe 0
    }

    @Test
    fun `an update naming no row logs nothing`() = runTest {
        mangaRepository.update(MangaUpdate(id = 99, favorite = true)) shouldBe true

        logCount() shouldBe 0
    }

    @Test
    fun `updateAll with a pair out and in logs each chapter of the entry coming in once and none of the one going out`() = runTest {
        // The migration's shape: one call takes one entry out of the library and puts another in.
        insertManga(id = 2, favorite = true)
        chapter(id = 20, mangaId = 2)
        chapter(id = 21, mangaId = 2, read = 1)
        exec("DELETE FROM sync_change_log")

        mangaRepository.updateAll(listOf(MangaUpdate(id = 2, favorite = false), MangaUpdate(id = 1, favorite = true))) shouldBe true

        logged("chapter").map { it.substringBefore(' ').toLong() }.sorted() shouldContainExactly listOf(10L, 11, 12, 13, 14, 15)
        logged("manga") shouldContainExactly listOf("2 upsert", "1 upsert")
    }
}
