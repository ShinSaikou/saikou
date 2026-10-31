package com.shinsaikou.sync.engine

import com.shinsaikou.sync.InMemorySyncBackend
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * What sync carries (phase 3 Q20): library rows only, and never a downloaded file. A read or a
 * tombstone a pull writes must not remove a download. Today only two of the owner's own actions
 * remove one (`DownloadManifest.delete`, from the chapter list's delete and the settings screen's
 * orphan sweep), and `:sync` cannot reach `:downloads` at all; this pins the database half: a pull
 * that reads, tombstones and removes chapters and tombstones their manga leaves every manifest row.
 */
class SyncPullDownloadsTest {

    private val backend = InMemorySyncBackend()
    private val a = SyncTestDevice(backend)
    private val b = SyncTestDevice(backend)

    @AfterEach
    fun close() {
        a.close()
        b.close()
    }

    private fun SyncTestDevice.download(chapterId: Long, mangaId: Long) = exec(
        """
        INSERT INTO downloads_manifest(chapter_id, manga_id, source_id, manga_title, manga_url, chapter_url,
            chapter_name, chapter_dir_name, page_count, size_bytes, date_downloaded)
        VALUES ($chapterId, $mangaId, ${SyncTestDevice.SOURCE}, 'Title', '/series/$mangaId', '/c/$chapterId',
            'Chapter', 'dir-$chapterId', 10, 1000, 1)
        """,
    )

    @Test
    fun `a pull that reads, tombstones and removes chapters and deletes their manga removes no download`() {
        a.addFavourite(1)
        (10L..12L).forEach { a.addChapter(it, mangaId = 1) }
        a.addFavourite(2)
        a.addChapter(20, mangaId = 2)
        a.sync()
        b.sync()
        val manifest = listOf(
            b.chapterId(b.mangaId(SyncTestDevice.SOURCE, "/series/1")!!, "/series/1/chapter/10")!!,
            b.chapterId(b.mangaId(SyncTestDevice.SOURCE, "/series/1")!!, "/series/1/chapter/11")!!,
            b.chapterId(b.mangaId(SyncTestDevice.SOURCE, "/series/1")!!, "/series/1/chapter/12")!!,
            b.chapterId(b.mangaId(SyncTestDevice.SOURCE, "/series/2")!!, "/series/2/chapter/20")!!,
        )
        manifest.forEach { b.download(it, mangaId = 0) }
        val before = b.rows("SELECT * FROM downloads_manifest ORDER BY chapter_id", 13)

        a.exec("UPDATE chapters SET read = 1 WHERE _id = 10")
        a.exec("UPDATE chapters SET deleted_at = strftime('%s', 'now') * 1000 WHERE _id = 11")
        a.exec("DELETE FROM chapters WHERE _id = 12")
        a.exec("UPDATE mangas SET favorite = 0, deleted_at = strftime('%s', 'now') * 1000 WHERE _id = 2")
        a.sync()
        b.sync().shouldNotBeNull()

        b.long("SELECT read FROM chapters WHERE _id = ${manifest[0]}") shouldBe 1
        b.long("SELECT count(*) FROM chapters WHERE deleted_at IS NOT NULL") shouldBe 2
        b.long("SELECT count(*) FROM mangas WHERE deleted_at IS NOT NULL") shouldBe 1
        b.rows("SELECT * FROM downloads_manifest ORDER BY chapter_id", 13) shouldBe before
    }
}
