package com.shinsaikou.data.repository

import app.cash.turbine.test
import com.shinsaikou.data.chapter.BookmarkNoteRepositoryImpl
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BookmarkNoteRepositoryImplTest : RepositoryTest() {

    private val repository by lazy { BookmarkNoteRepositoryImpl(handler) }

    @BeforeEach
    fun seed() {
        insertManga(id = 1)
        insertChapter(id = 10, mangaId = 1)
        insertChapter(id = 11, mangaId = 1)
    }

    @Test
    fun `upsert stores a note and returns it ordered by page index`() = runTest {
        repository.upsert(chapterId = 10, pageIndex = 4, note = "second", now = 200)
        repository.upsert(chapterId = 10, pageIndex = 1, note = "first", now = 100)

        repository.getByChapterId(10).map { it.pageIndex to it.note } shouldContainExactly
            listOf(1L to "first", 4L to "second")
    }

    @Test
    fun `upsert on the same page replaces the note text and keeps the original date`() = runTest {
        repository.upsert(chapterId = 10, pageIndex = 1, note = "before", now = 100)

        repository.upsert(chapterId = 10, pageIndex = 1, note = "after", now = 200)

        val notes = repository.getByChapterId(10)
        notes.size shouldBe 1
        notes.single().note shouldBe "after"
        notes.single().dateAdded shouldBe 100
    }

    @Test
    fun `subscribeByMangaId spans every chapter of the manga`() = runTest {
        repository.upsert(chapterId = 11, pageIndex = 0, note = "b", now = 100)
        repository.upsert(chapterId = 10, pageIndex = 0, note = "a", now = 100)

        repository.subscribeByMangaId(1).test {
            awaitItem().map { it.chapterId to it.note } shouldContainExactly
                listOf(10L to "a", 11L to "b")
        }
    }

    @Test
    fun `subscribeByChapterId re-emits when the note is removed`() = runTest {
        repository.upsert(chapterId = 10, pageIndex = 1, note = "n", now = 100)

        repository.subscribeByChapterId(10).test {
            awaitItem().size shouldBe 1

            repository.remove(chapterId = 10, pageIndex = 1)
            awaitItem() shouldBe emptyList()
        }
    }

    @Test
    fun `deleting the chapter cascades its notes away`() = runTest {
        repository.upsert(chapterId = 10, pageIndex = 1, note = "n", now = 100)

        exec("DELETE FROM chapters WHERE _id = 10")

        repository.getByChapterId(10) shouldBe emptyList()
    }
}
