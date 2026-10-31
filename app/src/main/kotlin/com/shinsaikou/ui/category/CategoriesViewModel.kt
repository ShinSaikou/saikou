package com.shinsaikou.ui.category

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.category.CategoryTree
import com.shinsaikou.domain.category.CategoryTreeNode
import com.shinsaikou.domain.category.interactor.SetCategoryParent
import com.shinsaikou.domain.category.interactor.SortCategoriesByName
import com.shinsaikou.i18n.R
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.interactor.ReorderCategory
import tachiyomi.domain.category.model.Category

/**
 * The category editor. Every mutation goes through an interactor and the list comes back from the
 * database subscription, so the rows on screen are the rows that were written rather than an
 * optimistic copy of them.
 *
 * The rows are [CategoryTree]'s tree of one level (phase 5 unit CT): a category under a top-level
 * one is listed under it, moves up and down among its siblings, and moves under another top-level
 * category or back to the top level through [SetCategoryParent]. The order is still one flat
 * `sort`, written by [ReorderCategory] as before, or all at once by [SortCategoriesByName].
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(CategoriesViewModel::class)
@Inject
class CategoriesViewModel(
    private val getCategories: GetCategories,
    private val createCategoryWithName: CreateCategoryWithName,
    private val renameCategory: RenameCategory,
    private val deleteCategory: DeleteCategory,
    private val reorderCategory: ReorderCategory,
    private val setCategoryParent: SetCategoryParent,
    private val sortCategoriesByName: SortCategoriesByName,
    private val dispatchers: Dispatchers,
) : StateViewModel<CategoriesViewModel.State>(State()) {

    data class State(
        val loading: Boolean = true,
        /** Every category but the system one, in `sort` order: the order [ReorderCategory] indexes. */
        val categories: List<Category> = emptyList(),
        /** [categories] as the screen lists them, each child under its parent. */
        val rows: List<CategoryTreeNode> = emptyList(),
        val dialog: Dialog? = null,
    ) {
        /** Whether a category renders under [category]: such a row moves under nothing (one level). */
        fun hasChildren(category: Category): Boolean = rows.any { it.parent?.id == category.id }

        /**
         * The top-level categories [category] may go under: every one but itself and the one it is
         * under now, and none when something is under [category] itself.
         */
        fun parentCandidates(category: Category): List<Category> {
            if (hasChildren(category)) return emptyList()
            val current = rows.firstOrNull { it.category.id == category.id }?.parent?.id
            return rows.filter { it.parent == null && it.category.id != category.id && it.category.id != current }
                .map { it.category }
        }

        /** [category]'s siblings in screen order: the top-level rows, or the rows under its parent. */
        fun siblings(category: Category): List<Category> {
            val parent = rows.firstOrNull { it.category.id == category.id }?.parent?.id
            return rows.filter { it.parent?.id == parent }.map { it.category }
        }
    }

    sealed interface Dialog {
        data object Create : Dialog
        data class Rename(val category: Category) : Dialog
        data class Delete(val category: Category) : Dialog

        /** "Move under...": the top-level categories [category] may go under. */
        data class MoveUnder(val category: Category, val candidates: List<Category>) : Dialog
    }

    sealed interface Event {
        data class Snackbar(@StringRes val messageRes: Int) : Event
    }

    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            getCategories.subscribe()
                // The system category has no name of its own and no position to move, so it never
                // reaches the list. Dropping it here also makes the screen's row index the index
                // ReorderCategory works in, which is the filtered list too.
                .map { categories ->
                    val listed = categories.filterNot(Category::isSystemCategory)
                    listed to CategoryTree.of(listed)
                }
                .flowOn(dispatchers.io)
                .collect { (categories, rows) ->
                    mutableState.update { it.copy(loading = false, categories = categories, rows = rows) }
                }
        }
    }

    fun showCreate() {
        mutableState.update { it.copy(dialog = Dialog.Create) }
    }

    fun showRename(category: Category) {
        mutableState.update { it.copy(dialog = Dialog.Rename(category)) }
    }

    fun showDelete(category: Category) {
        mutableState.update { it.copy(dialog = Dialog.Delete(category)) }
    }

    fun showMoveUnder(category: Category) {
        val candidates = state.value.parentCandidates(category)
        if (candidates.isEmpty()) return
        mutableState.update { it.copy(dialog = Dialog.MoveUnder(category, candidates)) }
    }

    fun dismissDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    fun create(name: String) {
        val trimmed = name.trim()
        if (!accepts(trimmed, renaming = null)) return
        dismissDialog()
        viewModelScope.launch {
            val result = withContext(dispatchers.io) { createCategoryWithName.await(trimmed) }
            if (result is CreateCategoryWithName.Result.InternalError) reportFailure()
        }
    }

    fun rename(category: Category, name: String) {
        val trimmed = name.trim()
        if (!accepts(trimmed, renaming = category)) return
        dismissDialog()
        viewModelScope.launch {
            val result = withContext(dispatchers.io) { renameCategory.await(category, trimmed) }
            if (result is RenameCategory.Result.InternalError) reportFailure()
        }
    }

    fun delete(category: Category) {
        dismissDialog()
        viewModelScope.launch {
            val result = withContext(dispatchers.io) { deleteCategory.await(category.id) }
            if (result is DeleteCategory.Result.InternalError) reportFailure()
        }
    }

    /** Puts [category] under [parent], a top-level category. */
    fun moveUnder(category: Category, parent: Category) {
        dismissDialog()
        setParent(category, parent.id)
    }

    /** Takes [category] out from under its parent. */
    fun moveToTopLevel(category: Category) {
        setParent(category, parentId = null)
    }

    private fun setParent(category: Category, parentId: Long?) {
        viewModelScope.launch {
            val result = withContext(dispatchers.io) { setCategoryParent.await(category.id, parentId) }
            if (result != SetCategoryParent.Result.Success) reportFailure()
        }
    }

    fun moveUp(category: Category) {
        move(category, offset = -1)
    }

    fun moveDown(category: Category) {
        move(category, offset = 1)
    }

    /**
     * Moves [category] past its neighbouring sibling, the top-level row above or below it or the
     * row above or below it under the same parent, by taking that sibling's place in the flat
     * order: the one `sort` stays the only order, and a child keeps its place among its siblings.
     */
    private fun move(category: Category, offset: Int) {
        val siblings = state.value.siblings(category)
        val index = siblings.indexOfFirst { it.id == category.id }
        val neighbour = siblings.getOrNull(index + offset)
        if (index == -1 || neighbour == null) return
        val target = state.value.categories.indexOfFirst { it.id == neighbour.id }
        if (target == -1) return
        viewModelScope.launch {
            val result = withContext(dispatchers.io) { reorderCategory.await(category, target) }
            if (result is ReorderCategory.Result.InternalError) reportFailure()
        }
    }

    /** The overflow's Sort A to Z: every level of the tree in name order, in one write. */
    fun sortByName() {
        viewModelScope.launch {
            val result = withContext(dispatchers.io) { sortCategoriesByName.await() }
            if (result is SortCategoriesByName.Result.InternalError) reportFailure()
        }
    }

    /**
     * Blank and duplicate names are refused before the interactor sees them. Nothing downstream
     * checks: the insert would succeed and leave the owner with two rows they cannot tell apart,
     * or with one that has no label at all. The comparison ignores case for the same reason.
     */
    private fun accepts(name: String, renaming: Category?): Boolean {
        val messageRes = when {
            name.isBlank() -> R.string.categories_error_empty_name
            state.value.categories.any { it.id != renaming?.id && it.name.equals(name, ignoreCase = true) } ->
                R.string.categories_error_exists
            else -> return true
        }
        viewModelScope.launch { eventChannel.send(Event.Snackbar(messageRes)) }
        return false
    }

    private suspend fun reportFailure() {
        eventChannel.send(Event.Snackbar(R.string.error_generic))
    }
}
