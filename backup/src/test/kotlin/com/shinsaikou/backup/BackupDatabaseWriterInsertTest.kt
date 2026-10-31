package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.File

/**
 * Phase 5 unit SC2, item 4: a restore inserts an entry's chapters ten to a statement and the rest
 * one at a time. The rows must be the ones a statement per chapter writes: every column, the latch
 * up, and ids in list order.
 */
class BackupDatabaseWriterInsertTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `chapters inserted ten to a statement are the rows one statement each writes`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val tens = insertEntry(env, "/tens")
            val single = insertEntry(env, "/single")

            // Two statements of ten and three single rows against twenty-three single rows.
            env.handler.await(inTransaction = true) { env.writer.insertChapters(chapters(tens)) }
            env.handler.await(inTransaction = true) {
                chapters(single).forEach { env.writer.insertChapters(listOf(it)) }
            }

            val rows = env.rows("chapters").groupBy { it["manga_id"] }
            val byTens = rows.getValue(tens)
            val bySingle = rows.getValue(single)
            byTens.size shouldBe COUNT
            byTens.map { it["url"] } shouldBe chapters(tens).map { it.url }
            byTens.map { it["_id"] as Long }.zipWithNext { a, b -> b - a }.distinct() shouldBe listOf(1L)
            byTens.map { it["is_syncing"] }.distinct() shouldBe listOf(1L)
            byTens.map { it.comparable() } shouldBe bySingle.map { it.comparable() }
        }
    }

    private suspend fun insertEntry(env: BackupTestEnvironment, url: String): Long =
        env.handler.await(inTransaction = true) {
            env.writer.insertManga(Manga.create().copy(source = 1L, url = url, title = url, favorite = true))
        }

    /** Every column varies, nulls included, and read and bookmark always differ, so a misplaced bind shows. */
    private fun chapters(mangaId: Long) = (0 until COUNT).map { i ->
        Chapter.create().copy(
            mangaId = mangaId,
            url = "/chapter/$i",
            name = "Chapter $i",
            scanlator = if (i % 3 == 0) null else "Group $i",
            read = i % 2 == 0,
            bookmark = i % 2 == 1,
            lastPageRead = i * 3L,
            chapterNumber = i + 0.5,
            sourceOrder = (COUNT - i).toLong(),
            dateFetch = 1_700_000_000_000L + i,
            dateUpload = 1_690_000_000_000L + i * 7,
            version = i + 2L,
            volume = if (i % 4 == 0) null else i / 4.0,
            deletedAt = if (i == 7) 1_710_000_000_000L else null,
        )
    }

    /** The row without what differs between the two entries by construction: its id and its entry. */
    private fun Map<String, Any?>.comparable() = filterKeys { it != "_id" && it != "manga_id" }
        .mapValues { (_, value) -> if (value is ByteArray) value.toList() else value }

    private companion object {
        const val COUNT = 23
    }
}
