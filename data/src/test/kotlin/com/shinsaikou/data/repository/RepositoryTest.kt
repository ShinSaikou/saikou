package com.shinsaikou.data.repository

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.shinsaikou.data.AndroidDatabaseHandler
import com.shinsaikou.data.DatabaseHandler
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import java.util.Properties

/**
 * Schema version 1 on the in-memory JVM driver, behind the real [DatabaseHandler], so the fresh
 * repositories of 05-data section 4.2 are exercised against the queries they actually run rather
 * than a mock. The dispatcher is [Dispatchers.Unconfined] so a `runTest` body sees writes without
 * having to idle the IO pool.
 *
 * No fixture names a real source, extension, catalog or key: `CONTRIBUTING.md` binds test data too.
 */
abstract class RepositoryTest {

    protected lateinit var driver: JdbcSqliteDriver
    protected lateinit var database: Database
    protected lateinit var handler: DatabaseHandler

    @BeforeEach
    fun setUpDatabase() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), Database.Schema.synchronous())
        exec("PRAGMA foreign_keys = ON")
        database = Database(
            driver = driver,
            chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
            historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
                genre_overrideAdapter = StringListColumnAdapter,
            ),
        )
        handler = AndroidDatabaseHandler(database, Dispatchers.Unconfined)
    }

    @AfterEach
    fun tearDownDatabase() {
        driver.close()
    }

    protected fun exec(sql: String) {
        driver.execute(null, sql, 0).value
    }

    protected fun scalar(sql: String): String? = driver.executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor: SqlCursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
        },
        parameters = 0,
    ).value

    protected fun insertManga(id: Long, favorite: Boolean = true) = exec(
        """
        INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer,
            chapter_flags, cover_last_modified, date_added, memo)
        VALUES ($id, 1, 'https://example.invalid/manga/$id', 'Title $id', 0, ${if (favorite) 1 else 0},
            0, 0, 0, 0, 0, CAST('{}' AS BLOB))
        """,
    )

    protected fun insertChapter(id: Long, mangaId: Long) = exec(
        """
        INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, last_page_read,
            chapter_number, source_order, date_fetch, date_upload, memo)
        VALUES ($id, $mangaId, 'https://example.invalid/ch/$id', 'Chapter $id', NULL,
            0, 0, 0, 1.0, 0, 0, 0, CAST('{}' AS BLOB))
        """,
    )
}
