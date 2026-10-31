package com.shinsaikou.data.repository

import app.cash.turbine.test
import com.shinsaikou.data.chapter.ScanlatorPriorityRepositoryImpl
import com.shinsaikou.domain.chapter.interactor.GetScanlatorPriority
import com.shinsaikou.domain.chapter.interactor.SetScanlatorPriority
import com.shinsaikou.domain.chapter.model.ScanlatorPriority
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Group names are placeholders throughout: `CONTRIBUTING.md` binds test fixtures too. */
class ScanlatorPriorityRepositoryImplTest : RepositoryTest() {

    private val repository by lazy { ScanlatorPriorityRepositoryImpl(handler) }

    @BeforeEach
    fun seed() {
        insertManga(id = 1)
        insertManga(id = 2)
    }

    @Test
    fun `setPriorities numbers the list from zero in order`() = runTest {
        repository.setPriorities(mangaId = 1, orderedScanlators = listOf("A", "B", "C"))

        repository.getByMangaId(1) shouldContainExactly listOf(
            ScanlatorPriority(mangaId = 1, scanlator = "A", priority = 0),
            ScanlatorPriority(mangaId = 1, scanlator = "B", priority = 1),
            ScanlatorPriority(mangaId = 1, scanlator = "C", priority = 2),
        )
    }

    @Test
    fun `setPriorities replaces the whole list rather than merging it`() = runTest {
        repository.setPriorities(mangaId = 1, orderedScanlators = listOf("A", "B", "C"))

        repository.setPriorities(mangaId = 1, orderedScanlators = listOf("C", "A"))

        repository.getByMangaId(1).map { it.scanlator } shouldContainExactly listOf("C", "A")
    }

    @Test
    fun `setPriorities leaves another manga's list alone`() = runTest {
        repository.setPriorities(mangaId = 1, orderedScanlators = listOf("A"))
        repository.setPriorities(mangaId = 2, orderedScanlators = listOf("B"))

        repository.setPriorities(mangaId = 1, orderedScanlators = listOf("C"))

        repository.getByMangaId(2).map { it.scanlator } shouldContainExactly listOf("B")
    }

    @Test
    fun `clear removes the manga's list`() = runTest {
        repository.setPriorities(mangaId = 1, orderedScanlators = listOf("A", "B"))

        repository.clear(1)

        repository.getByMangaId(1) shouldBe emptyList()
    }

    @Test
    fun `subscribeByMangaId re-emits when the list is set`() = runTest {
        repository.subscribeByMangaId(1).test {
            awaitItem() shouldBe emptyList()

            repository.setPriorities(mangaId = 1, orderedScanlators = listOf("A"))
            awaitItem().map { it.scanlator } shouldContainExactly listOf("A")
        }
    }

    /**
     * Phase 4 unit SC's one-shot read, as `removeDuplicates` takes it: the top group is stored as
     * 0 and read back as rank 0, and "Reset order" leaves the title with no rows at all.
     */
    @Test
    fun `the interactors write a ranking that reads back as group to rank, and clear unranks the title`() = runTest {
        SetScanlatorPriority(repository).await(mangaId = 1, orderedScanlators = listOf("B", "A"))

        GetScanlatorPriority(repository).await(1) shouldBe mapOf("B" to 0L, "A" to 1L)
        GetScanlatorPriority(repository).await(2) shouldBe emptyMap()

        SetScanlatorPriority(repository).clear(1)

        GetScanlatorPriority(repository).await(1) shouldBe emptyMap()
    }

    @Test
    fun `deleting the manga cascades its list away`() = runTest {
        repository.setPriorities(mangaId = 1, orderedScanlators = listOf("A"))

        exec("DELETE FROM mangas WHERE _id = 1")

        repository.getByMangaId(1) shouldBe emptyList()
    }
}
