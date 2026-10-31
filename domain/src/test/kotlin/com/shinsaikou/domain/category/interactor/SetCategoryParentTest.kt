package com.shinsaikou.domain.category.interactor

import com.shinsaikou.domain.category.CategorySyncKey
import com.shinsaikou.domain.preferences.LibraryPreferences
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.model.LibrarySort

/**
 * Phase 5 unit CT, item 1: the one-level rule in [SetCategoryParent] and [CreateCategoryWithName],
 * through the real interactors over an in-memory repository that stores what they write.
 */
class SetCategoryParentTest {

    private val repository = InMemoryCategoryRepository()
    private val setParent = SetCategoryParent(repository)
    private val preferences = mockk<LibraryPreferences> { every { sortingMode.get() } returns LibrarySort.default }
    private val create = CreateCategoryWithName(repository, preferences)

    private fun parentOf(id: Long): Long? = repository.rows.getValue(id).parentId

    @Test
    fun `a top-level category goes under another top-level category, and back to the top level`() = runTest {
        val reading = repository.add("Reading")
        val weekly = repository.add("Weekly")

        setParent.await(weekly, reading) shouldBe SetCategoryParent.Result.Success
        parentOf(weekly) shouldBe reading

        setParent.await(weekly, null) shouldBe SetCategoryParent.Result.Success
        parentOf(weekly) shouldBe null
    }

    @Test
    fun `a child moves from one top-level parent to another`() = runTest {
        val reading = repository.add("Reading")
        val finished = repository.add("Finished")
        val weekly = repository.add("Weekly", parentId = reading)

        setParent.await(weekly, finished) shouldBe SetCategoryParent.Result.Success
        parentOf(weekly) shouldBe finished
    }

    @Test
    fun `a parent that is itself under another is refused as too deep, and nothing is written`() = runTest {
        val reading = repository.add("Reading")
        val weekly = repository.add("Weekly", parentId = reading)
        val loose = repository.add("Loose")

        setParent.await(loose, weekly) shouldBe SetCategoryParent.Result.TooDeep
        parentOf(loose) shouldBe null
        repository.parentWrites shouldBe 0
    }

    @Test
    fun `a category that has a category under it is refused as too deep, and nothing is written`() = runTest {
        val reading = repository.add("Reading")
        repository.add("Weekly", parentId = reading)
        val finished = repository.add("Finished")

        setParent.await(reading, finished) shouldBe SetCategoryParent.Result.TooDeep
        parentOf(reading) shouldBe null
        repository.parentWrites shouldBe 0
    }

    @Test
    fun `the depth rule reads the tree, so a child of a deleted parent may take children`() = runTest {
        // The child's stored parent is soft-deleted, so the tree shows it at the top level and the
        // screens offer it as a parent; the rule must accept what the screens offer.
        val gone = repository.add("Gone")
        val child = repository.add("Child of gone", parentId = gone)
        repository.rows[gone] = repository.rows.getValue(gone).copy(deletedAt = 1_000L)
        val loose = repository.add("Loose")

        setParent.await(loose, child) shouldBe SetCategoryParent.Result.Success
        parentOf(loose) shouldBe child
    }

    @Test
    fun `the earlier refusals still hold`() = runTest {
        val reading = repository.add("Reading")

        setParent.await(reading, Category.UNCATEGORIZED_ID) shouldBe SetCategoryParent.Result.IllegalParent
        setParent.await(reading, reading) shouldBe SetCategoryParent.Result.IllegalParent
        setParent.await(reading, 999L) shouldBe SetCategoryParent.Result.IllegalParent
        repository.parentWrites shouldBe 0
    }

    @Test
    fun `a stored cycle the tree broke still refuses a write that would keep it`() = runTest {
        // A merge stored North under South and South under North; the tree shows one of them at the
        // top level, and nesting the other under it again is the cycle check's to refuse.
        val north = repository.add("North")
        val south = repository.add("South", parentId = north)
        repository.rows[north] = repository.rows.getValue(north).copy(parentId = south)
        val (top, under) = listOf(north, south).sortedBy { repository.rows.getValue(it).syncKey }

        setParent.await(under, top) shouldBe SetCategoryParent.Result.Cycle
    }

    @Test
    fun `create with no parent makes a top-level category, as before`() = runTest {
        create.await("Reading") shouldBe CreateCategoryWithName.Result.Success

        repository.rows.values.single { it.name == "Reading" }.parentId shouldBe null
    }

    @Test
    fun `create under a top-level category stores the parent`() = runTest {
        val reading = repository.add("Reading")

        create.await("Weekly", parentId = reading) shouldBe CreateCategoryWithName.Result.Success

        repository.rows.values.single { it.name == "Weekly" }.parentId shouldBe reading
    }

    @Test
    fun `create under a child is refused as too deep and creates nothing`() = runTest {
        val reading = repository.add("Reading")
        val weekly = repository.add("Weekly", parentId = reading)

        create.await("Monthly", parentId = weekly) shouldBe CreateCategoryWithName.Result.TooDeep

        repository.rows.values.map { it.name } shouldContainExactly listOf("", "Reading", "Weekly")
    }

    @Test
    fun `create under the system category or a category not held is refused and creates nothing`() = runTest {
        create.await("Monthly", parentId = Category.UNCATEGORIZED_ID).shouldBeInstanceOf<CreateCategoryWithName.Result.IllegalParent>()
        create.await("Monthly", parentId = 999L).shouldBeInstanceOf<CreateCategoryWithName.Result.IllegalParent>()

        repository.rows.values.map { it.name } shouldContainExactly listOf("")
    }

    /** Live rows only from [getAll], as the database's `getCategories` reads; the system one included. */
    private class InMemoryCategoryRepository : CategoryRepository {
        val rows = linkedMapOf(
            Category.UNCATEGORIZED_ID to Category(id = Category.UNCATEGORIZED_ID, name = "", order = -1, flags = 0),
        )
        var parentWrites = 0
        private var nextId = 1L

        fun add(name: String, parentId: Long? = null): Long {
            val id = nextId++
            rows[id] = Category(id, name, order = id, flags = 0, parentId = parentId, syncKey = CategorySyncKey.of(name))
            return id
        }

        override suspend fun get(id: Long): Category? = rows[id]

        override suspend fun getAll(): List<Category> = rows.values.filter { it.deletedAt == null }.sortedBy { it.order }

        override fun getAllAsFlow(): Flow<List<Category>> = MutableStateFlow(rows.values.toList())

        override suspend fun getCategoriesByMangaId(mangaId: Long): List<Category> = emptyList()

        override fun getCategoriesByMangaIdAsFlow(mangaId: Long): Flow<List<Category>> = MutableStateFlow(emptyList())

        override suspend fun insert(category: Category) {
            add(category.name, category.parentId)
        }

        override suspend fun updateName(categoryId: Long, name: String) = error("not used")

        override suspend fun updateFlags(categoryId: Long, flags: Long) = error("not used")

        override suspend fun updateAllFlags(flags: Long?) = error("not used")

        override suspend fun updateAllOrders(orderedIds: List<Long>) = error("not used")

        override suspend fun delete(categoryId: Long) = error("not used")

        override suspend fun updateParent(categoryId: Long, parentId: Long?) {
            parentWrites++
            rows[categoryId] = rows.getValue(categoryId).copy(parentId = parentId)
        }

        override suspend fun restore(categoryIds: List<Long>) = error("not used")
    }
}
