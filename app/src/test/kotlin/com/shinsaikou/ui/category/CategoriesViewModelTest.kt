package com.shinsaikou.ui.category

import app.cash.turbine.test
import com.shinsaikou.domain.category.interactor.SetCategoryParent
import com.shinsaikou.domain.category.interactor.SortCategoriesByName
import com.shinsaikou.i18n.R
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.interactor.ReorderCategory
import tachiyomi.domain.category.model.Category
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers

/**
 * Covers the state the category list settles into once the system category is filtered out,
 * the failure branch of every mutation (each interactor returns a result rather than throwing,
 * so a snackbar is the only signal a caller gets), and the boundary checks in move() that keep
 * ReorderCategory from ever being asked for an out of range index.
 */
class CategoriesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val getCategories = mockk<GetCategories>()
    private val createCategoryWithName = mockk<CreateCategoryWithName>()
    private val renameCategory = mockk<RenameCategory>()
    private val deleteCategory = mockk<DeleteCategory>()
    private val reorderCategory = mockk<ReorderCategory>()
    private val setCategoryParent = mockk<SetCategoryParent>()
    private val sortCategoriesByName = mockk<SortCategoriesByName>()

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    private fun viewModel() = CategoriesViewModel(
        getCategories = getCategories,
        createCategoryWithName = createCategoryWithName,
        renameCategory = renameCategory,
        deleteCategory = deleteCategory,
        reorderCategory = reorderCategory,
        setCategoryParent = setCategoryParent,
        sortCategoriesByName = sortCategoriesByName,
        dispatchers = AppDispatchers(
            io = testDispatcher,
            default = testDispatcher,
            main = testDispatcher,
        ),
    )

    private val systemCategory = Category(id = Category.UNCATEGORIZED_ID, name = "", order = 0, flags = 0)
    private val fixtureOne = Category(id = 1L, name = "Fixture One", order = 0, flags = 0)
    private val fixtureTwo = Category(id = 2L, name = "Fixture Two", order = 1, flags = 0)
    private val fixtureThree = Category(id = 3L, name = "Fixture Three", order = 2, flags = 0)

    @Test
    fun `the category list reaches state with the system category filtered out and loading false`() = runTest(testDispatcher) {
        every { getCategories.subscribe() } returns flowOf(listOf(systemCategory, fixtureOne, fixtureTwo))

        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.state.value.loading shouldBe false
        viewModel.state.value.categories shouldContainExactly listOf(fixtureOne, fixtureTwo)
    }

    @Test
    fun `create with a blank name reports the empty name error and does not call the interactor`() = runTest(testDispatcher) {
        every { getCategories.subscribe() } returns flowOf(listOf(fixtureOne))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.create("   ")

            awaitItem() shouldBe CategoriesViewModel.Event.Snackbar(R.string.categories_error_empty_name)
        }
        coVerify(exactly = 0) { createCategoryWithName.await(any()) }
    }

    @Test
    fun `create with a name that already exists case insensitive reports the exists error and does not call the interactor`() = runTest(testDispatcher) {
        every { getCategories.subscribe() } returns flowOf(listOf(fixtureOne))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.create("fixture one")

            awaitItem() shouldBe CategoriesViewModel.Event.Snackbar(R.string.categories_error_exists)
        }
        coVerify(exactly = 0) { createCategoryWithName.await(any()) }
    }

    @Test
    fun `create with a valid name calls CreateCategoryWithName and reports a snackbar on internal error`() = runTest(testDispatcher) {
        every { getCategories.subscribe() } returns flowOf(listOf(fixtureOne))
        coEvery { createCategoryWithName.await("Fixture New") } returns
            CreateCategoryWithName.Result.InternalError(RuntimeException("boom"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.create("Fixture New")
            advanceUntilIdle()

            awaitItem() shouldBe CategoriesViewModel.Event.Snackbar(R.string.error_generic)
        }
        coVerify(exactly = 1) { createCategoryWithName.await("Fixture New") }
    }

    @Test
    fun `rename to the category's own current name is accepted, not treated as a duplicate`() = runTest(testDispatcher) {
        every { getCategories.subscribe() } returns flowOf(listOf(fixtureOne))
        coEvery { renameCategory.await(any<Category>(), any()) } returns RenameCategory.Result.Success
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.rename(fixtureOne, "Fixture One")
        advanceUntilIdle()

        coVerify(exactly = 1) { renameCategory.await(fixtureOne, "Fixture One") }
    }

    @Test
    fun `rename to a blank name reports the empty name error and does not call the interactor`() = runTest(testDispatcher) {
        every { getCategories.subscribe() } returns flowOf(listOf(fixtureOne))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.rename(fixtureOne, " ")

            awaitItem() shouldBe CategoriesViewModel.Event.Snackbar(R.string.categories_error_empty_name)
        }
        coVerify(exactly = 0) { renameCategory.await(any<Category>(), any()) }
    }

    @Test
    fun `rename to a name used by another category reports the exists error and does not call the interactor`() = runTest(testDispatcher) {
        every { getCategories.subscribe() } returns flowOf(listOf(fixtureOne, fixtureTwo))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.rename(fixtureOne, "fixture two")

            awaitItem() shouldBe CategoriesViewModel.Event.Snackbar(R.string.categories_error_exists)
        }
        coVerify(exactly = 0) { renameCategory.await(any<Category>(), any()) }
    }

    @Test
    fun `rename with a valid name calls RenameCategory and reports a snackbar on internal error`() = runTest(testDispatcher) {
        every { getCategories.subscribe() } returns flowOf(listOf(fixtureOne))
        coEvery { renameCategory.await(fixtureOne, "Renamed") } returns
            RenameCategory.Result.InternalError(RuntimeException("boom"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.rename(fixtureOne, "Renamed")
            advanceUntilIdle()

            awaitItem() shouldBe CategoriesViewModel.Event.Snackbar(R.string.error_generic)
        }
        coVerify(exactly = 1) { renameCategory.await(fixtureOne, "Renamed") }
    }

    @Test
    fun `delete calls DeleteCategory with the category id and reports a snackbar on internal error`() = runTest(testDispatcher) {
        every { getCategories.subscribe() } returns flowOf(listOf(fixtureOne))
        coEvery { deleteCategory.await(fixtureOne.id) } returns
            DeleteCategory.Result.InternalError(RuntimeException("boom"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.delete(fixtureOne)
            advanceUntilIdle()

            awaitItem() shouldBe CategoriesViewModel.Event.Snackbar(R.string.error_generic)
        }
        coVerify(exactly = 1) { deleteCategory.await(fixtureOne.id) }
    }

    @Test
    fun `sort A to Z writes through SortCategoriesByName and reports a snackbar on internal error`() =
        runTest(testDispatcher) {
            every { getCategories.subscribe() } returns flowOf(listOf(fixtureOne, fixtureTwo, fixtureThree))
            coEvery { sortCategoriesByName.await() } returns
                SortCategoriesByName.Result.InternalError(RuntimeException("boom"))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.events.test {
                viewModel.sortByName()
                advanceUntilIdle()

                awaitItem() shouldBe CategoriesViewModel.Event.Snackbar(R.string.error_generic)
            }
            coVerify(exactly = 1) { sortCategoriesByName.await() }
        }

    @Test
    fun `moveUp on the first row does nothing`() = runTest(testDispatcher) {
        every { getCategories.subscribe() } returns flowOf(listOf(fixtureOne, fixtureTwo, fixtureThree))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.moveUp(fixtureOne)
        advanceUntilIdle()

        coVerify(exactly = 0) { reorderCategory.await(any(), any()) }
    }

    @Test
    fun `moveDown on the last row does nothing`() = runTest(testDispatcher) {
        every { getCategories.subscribe() } returns flowOf(listOf(fixtureOne, fixtureTwo, fixtureThree))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.moveDown(fixtureThree)
        advanceUntilIdle()

        coVerify(exactly = 0) { reorderCategory.await(any(), any()) }
    }

    @Test
    fun `moveUp in the middle calls ReorderCategory with the expected target index`() = runTest(testDispatcher) {
        every { getCategories.subscribe() } returns flowOf(listOf(fixtureOne, fixtureTwo, fixtureThree))
        coEvery { reorderCategory.await(fixtureTwo, 0) } returns ReorderCategory.Result.Success
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.moveUp(fixtureTwo)
        advanceUntilIdle()

        coVerify(exactly = 1) { reorderCategory.await(fixtureTwo, 0) }
    }

    @Test
    fun `moveDown in the middle calls ReorderCategory with the expected target index`() = runTest(testDispatcher) {
        every { getCategories.subscribe() } returns flowOf(listOf(fixtureOne, fixtureTwo, fixtureThree))
        coEvery { reorderCategory.await(fixtureTwo, 2) } returns ReorderCategory.Result.Success
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.moveDown(fixtureTwo)
        advanceUntilIdle()

        coVerify(exactly = 1) { reorderCategory.await(fixtureTwo, 2) }
    }

    // Phase 5 unit CT: the tree, sibling moves and the parent's two moves.

    private val parentOne = Category(id = 11L, name = "Parent One", order = 0, flags = 0)
    private val childA = Category(id = 12L, name = "Child A", order = 1, flags = 0, parentId = 11L)
    private val parentTwo = Category(id = 13L, name = "Parent Two", order = 2, flags = 0)
    private val childB = Category(id = 14L, name = "Child B", order = 3, flags = 0, parentId = 11L)
    private val loose = Category(id = 15L, name = "Loose", order = 4, flags = 0)
    private val tree = listOf(systemCategory, parentOne, childA, parentTwo, childB, loose)

    @Test
    fun `the rows list each child under its parent, and the flat list keeps sort order for the reorder`() = runTest(testDispatcher) {
        every { getCategories.subscribe() } returns flowOf(tree)
        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        state.rows.map { it.category.name to it.depth } shouldContainExactly listOf(
            "Parent One" to 0, "Child A" to 1, "Child B" to 1, "Parent Two" to 0, "Loose" to 0,
        )
        state.categories shouldContainExactly listOf(parentOne, childA, parentTwo, childB, loose)
    }

    @Test
    fun `moveDown on a child moves it past the next child of the same parent, by that child's flat index`() = runTest(testDispatcher) {
        every { getCategories.subscribe() } returns flowOf(tree)
        coEvery { reorderCategory.await(childA, 3) } returns ReorderCategory.Result.Success
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.moveDown(childA)
        advanceUntilIdle()

        coVerify(exactly = 1) { reorderCategory.await(childA, 3) }
    }

    @Test
    fun `moveDown on the last child of a parent does nothing, though rows follow it`() = runTest(testDispatcher) {
        every { getCategories.subscribe() } returns flowOf(tree)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.moveDown(childB)
        advanceUntilIdle()

        coVerify(exactly = 0) { reorderCategory.await(any(), any()) }
    }

    @Test
    fun `moveUp on a top-level row moves it past the top-level row above, not past that row's children`() = runTest(testDispatcher) {
        every { getCategories.subscribe() } returns flowOf(tree)
        coEvery { reorderCategory.await(parentTwo, 0) } returns ReorderCategory.Result.Success
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.moveUp(parentTwo)
        advanceUntilIdle()

        coVerify(exactly = 1) { reorderCategory.await(parentTwo, 0) }
    }

    @Test
    fun `move under offers the other top-level categories, and none to a row with children`() = runTest(testDispatcher) {
        every { getCategories.subscribe() } returns flowOf(tree)
        val viewModel = viewModel()
        advanceUntilIdle()
        val state = viewModel.state.value

        state.parentCandidates(loose) shouldContainExactly listOf(parentOne, parentTwo)
        // A child is offered every top-level category but the one it is under.
        state.parentCandidates(childA) shouldContainExactly listOf(parentTwo, loose)
        state.parentCandidates(parentOne) shouldContainExactly emptyList()

        viewModel.showMoveUnder(parentOne)
        viewModel.state.value.dialog shouldBe null
        viewModel.showMoveUnder(loose)
        viewModel.state.value.dialog shouldBe CategoriesViewModel.Dialog.MoveUnder(loose, listOf(parentOne, parentTwo))
    }

    @Test
    fun `move under and move to top level write through SetCategoryParent, and a refusal is a snackbar`() = runTest(testDispatcher) {
        every { getCategories.subscribe() } returns flowOf(tree)
        coEvery { setCategoryParent.await(loose.id, parentTwo.id) } returns SetCategoryParent.Result.Success
        coEvery { setCategoryParent.await(childA.id, null) } returns SetCategoryParent.Result.TooDeep
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.showMoveUnder(loose)

        viewModel.events.test {
            viewModel.moveUnder(loose, parentTwo)
            advanceUntilIdle()
            viewModel.state.value.dialog shouldBe null

            viewModel.moveToTopLevel(childA)
            advanceUntilIdle()
            awaitItem() shouldBe CategoriesViewModel.Event.Snackbar(R.string.error_generic)
        }
        coVerify(exactly = 1) { setCategoryParent.await(loose.id, parentTwo.id) }
        coVerify(exactly = 1) { setCategoryParent.await(childA.id, null) }
    }
}
