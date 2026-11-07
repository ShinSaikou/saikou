package com.shinsaikou.reader.state

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.data.AndroidDatabaseHandler
import com.shinsaikou.data.DatabaseTransactionRunner
import com.shinsaikou.data.download.DownloadManifestRepositoryImpl
import com.shinsaikou.domain.chapter.ChapterReplacements
import com.shinsaikou.domain.chapter.interactor.GetExcludedScanlators
import com.shinsaikou.domain.chapter.interactor.SyncChaptersWithSource
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.TrackPreferences
import com.shinsaikou.source.model.ChapterInfo
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.history.interactor.UpsertHistory
import java.util.Properties

/**
 * A source refresh that replaces the chapter a reader has open, by listing it under a new url, over
 * the real schema, repositories and refresh. The reader kept writing to the old id. A new row takes
 * one more than the largest id left, so once the refresh had removed the entry's last rows, that id
 * could name another of its chapters, and the reader's page and history row went there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressCommitterReplacedChapterTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var handler: AndroidDatabaseHandler
    private lateinit var chapters: ChapterRepositoryImpl
    private lateinit var replacements: ChapterReplacements

    @BeforeEach
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), Database.Schema.synchronous())
        exec("PRAGMA foreign_keys = ON")
        val database = Database(
            driver = driver,
            chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
            historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
            ),
        )
        handler = AndroidDatabaseHandler(database, testDispatcher)
        chapters = ChapterRepositoryImpl(handler)
        replacements = ChapterReplacements(chapters, DatabaseTransactionRunner(handler))
        exec(
            """
            INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer,
                chapter_flags, cover_last_modified, date_added, memo)
            VALUES ($MANGA_ID, 1, '/manga', 'title', 0, 1, 0, 0, 0, 0, 0, CAST('{}' AS BLOB))
            """,
        )
        for (id in 1L..2L) {
            exec(
                """
                INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, last_page_read,
                    chapter_number, source_order, date_fetch, date_upload, memo)
                VALUES ($id, $MANGA_ID, '/$id', 'Chapter $id', NULL, 0, 0, 0, $id, ${2 - id}, 1, 1,
                    CAST('{}' AS BLOB))
                """,
            )
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a page and a history row after the refresh go to the chapter that replaced the open one`() =
        runTest(testDispatcher) {
            val committer = committer()
            val open = ReaderChapter(checkNotNull(chapters.getChapterById(1L)))

            // Both chapters move to new urls, listed newest first. The new rows take the old ids the
            // other way round: chapter 2 is row 1 now.
            refresh(ChapterInfo(url = "/2b", name = "Chapter 2"), ChapterInfo(url = "/1b", name = "Chapter 1"))
            id("/2b") shouldBe 1L

            committer.onPageSelected(open, 5, chapterFinished = false, immediate = true)
            advanceUntilIdle()
            committer.commitHistory(open)

            lastPageRead("/1b") shouldBe 5L
            lastPageRead("/2b") shouldBe 0L
            historyChapterIds() shouldBe listOf(id("/1b"))
        }

    @Test
    fun `a chapter removed with nothing in its place gets no write, and the chapter on its old id neither`() =
        runTest(testDispatcher) {
            val committer = committer()
            val open = ReaderChapter(checkNotNull(chapters.getChapterById(2L)))

            // Chapter 2 is gone and chapter 9 is new, on row 2.
            refresh(ChapterInfo(url = "/1", name = "Chapter 1"), ChapterInfo(url = "/9", name = "Chapter 9"))
            id("/9") shouldBe 2L

            committer.onPageSelected(open, 5, chapterFinished = true, immediate = true)
            advanceUntilIdle()
            committer.commitHistory(open)

            lastPageRead("/9") shouldBe 0L
            long("SELECT read FROM chapters WHERE url = '/9'") shouldBe 0L
            historyChapterIds() shouldBe emptyList()
        }

    private suspend fun refresh(vararg listed: ChapterInfo) {
        SyncChaptersWithSource(
            chapterRepository = chapters,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = mockk(relaxed = true),
            getChaptersByMangaId = GetChaptersByMangaId(chapters),
            transactionRunner = DatabaseTransactionRunner(handler),
            downloadManifestRepository = DownloadManifestRepositoryImpl(handler),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            getExcludedScanlators = mockk<GetExcludedScanlators> { coEvery { await(any()) } returns emptySet() },
            historyRepository = HistoryRepositoryImpl(handler),
            chapterReplacements = replacements,
        ).await(listed.toList(), manga())
    }

    /** The real committer over the real chapter and history tables; the rest is out of the way. */
    private fun TestScope.committer() = ProgressCommitter(
        scope = this,
        updateChapter = UpdateChapter(chapters),
        upsertHistory = UpsertHistory(HistoryRepositoryImpl(handler)),
        setReadStatus = mockk(relaxed = true),
        getChaptersByMangaId = GetChaptersByMangaId(chapters),
        libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
        trackPreferences = TrackPreferences(InMemoryPreferenceStore()),
        trackProgress = mockk(relaxed = true),
        autoDownload = mockk(relaxed = true),
        readChapterCleanup = mockk(relaxed = true),
        reconcileMergedRead = mockk(relaxed = true),
        chapterReplacements = replacements,
        dispatchers = Dispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher),
    )

    private fun id(url: String): Long? = long("SELECT _id FROM chapters WHERE url = '$url'")

    private fun lastPageRead(url: String): Long? = long("SELECT last_page_read FROM chapters WHERE url = '$url'")

    private fun historyChapterIds(): List<Long> = driver.executeQuery(
        identifier = null,
        sql = "SELECT chapter_id FROM history ORDER BY chapter_id",
        mapper = { cursor: SqlCursor ->
            QueryResult.Value(buildList { while (cursor.next().value) add(checkNotNull(cursor.getLong(0))) })
        },
        parameters = 0,
    ).value

    private fun long(sql: String): Long? = driver.executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor: SqlCursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
        parameters = 0,
    ).value

    private fun exec(sql: String) {
        driver.execute(null, sql, 0).value
    }
}
