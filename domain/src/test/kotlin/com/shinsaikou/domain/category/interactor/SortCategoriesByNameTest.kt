package com.shinsaikou.domain.category.interactor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

/** Sort A to Z orders each level of the one-level tree by name and writes one flat order. */
class SortCategoriesByNameTest {

    private val repository = mockk<CategoryRepository>()
    private val sort = SortCategoriesByName(repository)

    private fun category(id: Long, name: String, order: Long, parentId: Long? = null) =
        Category(id = id, name = name, order = order, flags = 0, parentId = parentId)

    @Test
    fun `top-level categories and each parent's children are sorted by name, children under their parent`() =
        runTest {
            coEvery { repository.getAll() } returns listOf(
                category(Category.UNCATEGORIZED_ID, "", order = 0),
                category(1, "reading", order = 1),
                category(2, "Weekly", order = 2, parentId = 1),
                category(3, "Archive", order = 3),
                category(4, "Daily", order = 4, parentId = 1),
                category(5, "Zines", order = 5),
                category(6, "Old", order = 6, parentId = 3),
            )
            val written = slot<List<Long>>()
            coEvery { repository.updateAllOrders(capture(written)) } returns Unit

            sort.await() shouldBe SortCategoriesByName.Result.Success

            // Archive, its Old; reading (case does not count), its Daily then Weekly; Zines.
            written.captured shouldContainExactly listOf(3L, 6L, 1L, 4L, 2L, 5L)
        }

    @Test
    fun `an order already by name writes nothing`() = runTest {
        coEvery { repository.getAll() } returns listOf(
            category(1, "Archive", order = 0),
            category(2, "Reading", order = 1),
        )

        sort.await() shouldBe SortCategoriesByName.Result.Unchanged
        coVerify(exactly = 0) { repository.updateAllOrders(any()) }
    }

    @Test
    fun `a failed read is an internal error, not a crash`() = runTest {
        val failure = IllegalStateException("database closed")
        coEvery { repository.getAll() } throws failure

        sort.await() shouldBe SortCategoriesByName.Result.InternalError(failure)
        coVerify(exactly = 0) { repository.updateAllOrders(any()) }
    }

    @Test
    fun `a cancelled sort stays cancelled`() = runTest {
        coEvery { repository.getAll() } throws CancellationException("left the screen")

        shouldThrow<CancellationException> { sort.await() }
    }
}
