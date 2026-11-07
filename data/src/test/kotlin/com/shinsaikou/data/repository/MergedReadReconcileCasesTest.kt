package com.shinsaikou.data.repository

import com.shinsaikou.data.merged.MergedGroupRepositoryImpl
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The cases of `reconcileRead` (merged_groups.sq) that [MergedReadReconcileTest] leaves out: which
 * members and which numbers take part, what it raises inside one member, and what the raise costs
 * the rest of the schema. Entries 1 to 3 are favourites, merged in that order unless a test says
 * otherwise.
 */
class MergedReadReconcileCasesTest : RepositoryTest() {

    private val repository by lazy { MergedGroupRepositoryImpl(handler) }

    @BeforeEach
    fun seed() {
        (1L..3L).forEach { insertManga(id = it) }
    }

    private fun group(): Long = scalar("SELECT _id FROM merged_groups")!!.toLong()

    private fun chapter(id: Long, mangaId: Long, number: Double, read: Int = 0, scanlator: String? = null, deletedAt: Long? = null) =
        exec(
            """
            INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, last_page_read,
                chapter_number, source_order, date_fetch, date_upload, memo, deleted_at)
            VALUES ($id, $mangaId, '/c/$id', 'Chapter $id', ${scanlator?.let { "'$it'" } ?: "NULL"}, $read, 0, 0,
                $number, $id, 0, 0, CAST('{}' AS BLOB), ${deletedAt ?: "NULL"})
            """,
        )

    private fun read(id: Long): String? = scalar("SELECT read FROM chapters WHERE _id = $id")

    private suspend fun mergeAll() = repository.merge(mainId = 1, mangaIds = listOf(1, 2, 3))

    @Test
    fun `a member that was soft-deleted takes no part, and joins again once it is re-added`() = runTest {
        mergeAll()
        chapter(10, mangaId = 1, number = 1.0, read = 1)
        chapter(20, mangaId = 2, number = 1.0)
        chapter(21, mangaId = 2, number = 2.0, read = 1)
        chapter(30, mangaId = 3, number = 2.0)
        exec("UPDATE mangas SET deleted_at = 1000 WHERE _id = 2")

        repository.reconcileRead(group())

        // Neither raised by the deleted member nor raising for it.
        read(20) shouldBe "0"
        read(30) shouldBe "0"

        exec("UPDATE mangas SET deleted_at = NULL WHERE _id = 2")
        repository.reconcileRead(group())

        read(20) shouldBe "1"
        read(30) shouldBe "1"
    }

    @Test
    fun `a member that left the library takes no part`() = runTest {
        mergeAll()
        chapter(10, mangaId = 1, number = 1.0, read = 1)
        chapter(20, mangaId = 2, number = 1.0)
        exec("UPDATE mangas SET favorite = 0 WHERE _id = 2")

        repository.reconcileRead(group())

        read(20) shouldBe "0"
    }

    @Test
    fun `a chapter with a number below zero is never raised and never raises, however many copies it has`() = runTest {
        mergeAll()
        chapter(10, mangaId = 1, number = -1.0, read = 1)
        chapter(20, mangaId = 2, number = -1.0)
        chapter(30, mangaId = 3, number = -1.0)
        chapter(11, mangaId = 1, number = -0.5, read = 1)
        chapter(21, mangaId = 2, number = -0.5)

        repository.reconcileRead(group())

        listOf(20L, 30L, 21L).forEach { read(it) shouldBe "0" }
    }

    @Test
    fun `chapter zero is a recognised number`() = runTest {
        mergeAll()
        chapter(10, mangaId = 1, number = 0.0, read = 1)
        chapter(20, mangaId = 2, number = 0.0)

        repository.reconcileRead(group())

        read(20) shouldBe "1"
    }

    @Test
    fun `it only raises, whatever the mix of copies`() = runTest {
        mergeAll()
        chapter(10, mangaId = 1, number = 1.0, read = 1)
        chapter(11, mangaId = 1, number = 2.0)
        chapter(20, mangaId = 2, number = 1.0)
        chapter(21, mangaId = 2, number = 2.0, read = 1)
        chapter(30, mangaId = 3, number = 3.0)
        val readBefore = scalar("SELECT group_concat(_id) FROM chapters WHERE read = 1 ORDER BY _id")

        repository.reconcileRead(group())

        // Everything read before is still read, the two missing copies are raised, number 3 has no reader.
        scalar("SELECT count(*) FROM chapters WHERE read = 1") shouldBe "4"
        readBefore shouldBe "10,21"
        read(11) shouldBe "1"
        read(20) shouldBe "1"
        read(30) shouldBe "0"
    }

    /**
     * Copies inside one member count as copies. SetReadStatus expands a mark the same way (it reads
     * every member's list, the marked one included), so a number is one chapter to a merged entry
     * whichever source or scanlator a row comes from. Pinned because the design is silent on it.
     */
    @Test
    fun `a scanlator's copy inside one member is raised by its sibling's read mark`() = runTest {
        mergeAll()
        chapter(10, mangaId = 1, number = 5.0, read = 1, scanlator = "X")
        chapter(11, mangaId = 1, number = 5.0, scanlator = "Y")
        chapter(12, mangaId = 1, number = 5.0, scanlator = "Z", deletedAt = 1_000)

        repository.reconcileRead(group())

        read(11) shouldBe "1"
        read(12) shouldBe "0"
    }

    /**
     * The statement looks at no exclusion: a chapter of an excluded scanlator is a copy like any
     * other, raised when a visible copy was read and raising the others when it was read itself.
     * The design is silent on it (section 4), so this pins what the statement does today; the
     * exclusion itself stays as it was.
     */
    @Test
    fun `an excluded scanlator's copy is a copy, and the exclusion is left alone`() = runTest {
        mergeAll()
        exec("INSERT INTO excluded_scanlators(manga_id, scanlator) VALUES (2, 'X')")
        chapter(10, mangaId = 1, number = 1.0, read = 1)
        chapter(20, mangaId = 2, number = 1.0, scanlator = "X")
        chapter(21, mangaId = 2, number = 2.0, read = 1, scanlator = "X")
        chapter(30, mangaId = 3, number = 2.0)

        repository.reconcileRead(group())

        read(20) shouldBe "1"
        read(30) shouldBe "1"
        scalar("SELECT group_concat(scanlator) FROM excluded_scanlators WHERE manga_id = 2") shouldBe "X"
    }

    @Test
    fun `a raise keeps the library counts and the change log true to the rows`() = runTest {
        mergeAll()
        chapter(10, mangaId = 1, number = 1.0, read = 1)
        chapter(20, mangaId = 2, number = 1.0)
        chapter(21, mangaId = 2, number = 2.0)
        exec("DELETE FROM sync_change_log")

        repository.reconcileRead(group())

        scalar("SELECT readCount FROM libraryView WHERE _id = 2") shouldBe "1"
        scalar("SELECT readCount FROM libraryView WHERE _id = 1") shouldBe "1"
        // The raised chapter goes out as an ordinary change; the one left unread logs nothing.
        scalar("SELECT group_concat(entity_id) FROM sync_change_log WHERE entity_type = 'chapter'") shouldBe "20"
    }

    @Test
    fun `a group's reconcile leaves another group with the same numbers alone`() = runTest {
        (4L..5L).forEach { insertManga(id = it) }
        repository.merge(mainId = 1, mangaIds = listOf(1, 2))
        repository.merge(mainId = 4, mangaIds = listOf(4, 5))
        chapter(10, mangaId = 1, number = 1.0, read = 1)
        chapter(20, mangaId = 2, number = 1.0)
        chapter(40, mangaId = 4, number = 1.0)
        chapter(50, mangaId = 5, number = 1.0)
        val first = scalar("SELECT _id FROM merged_groups WHERE main_url LIKE '%/1'")!!.toLong()

        repository.reconcileRead(first)

        read(20) shouldBe "1"
        read(40) shouldBe "0"
        read(50) shouldBe "0"
    }
}
