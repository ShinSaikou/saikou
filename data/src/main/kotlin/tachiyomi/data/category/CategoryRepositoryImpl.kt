// Vendored from upstream v0.20.4: data/src/main/java/tachiyomi/data/category/CategoryRepositoryImpl.kt (modified: yes)
package tachiyomi.data.category

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.shinsaikou.data.DatabaseHandler
import com.shinsaikou.data.backfillCategorySyncKeys
import com.shinsaikou.domain.category.CategorySyncKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

/**
 * Modified: the constructor takes [DatabaseHandler], the mapper moved out to [CategoryMapper],
 * `insert` carries `parentId`, `updateParent` is added, and `delete` is a soft delete that also
 * reparents the children and drops the category's `mangas_categories` rows (05-data 3.2, 4.2).
 * `insert` sets the sync key and restores a deleted row that holds it, and `restore` is added
 * (phase 3 unit C, Q7). Every write that changes a name or a tombstone first keys the rows the
 * start-up backfill has not reached yet, since the key it owes them is derived from what they hold
 * now.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CategoryRepositoryImpl(
    private val handler: DatabaseHandler,
) : CategoryRepository {

    override suspend fun get(id: Long): Category? {
        return handler.awaitOneOrNull { categoriesQueries.getCategory(id, CategoryMapper::mapCategory) }
    }

    override suspend fun getAll(): List<Category> {
        return handler.awaitList { categoriesQueries.getCategories(CategoryMapper::mapCategory) }
    }

    override fun getAllAsFlow(): Flow<List<Category>> {
        return handler.subscribeToList { categoriesQueries.getCategories(CategoryMapper::mapCategory) }
    }

    override suspend fun getCategoriesByMangaId(mangaId: Long): List<Category> {
        return handler.awaitList {
            categoriesQueries.getCategoriesByMangaId(mangaId, CategoryMapper::mapCategory)
        }
    }

    override fun getCategoriesByMangaIdAsFlow(mangaId: Long): Flow<List<Category>> {
        return handler.subscribeToList {
            categoriesQueries.getCategoriesByMangaId(mangaId, CategoryMapper::mapCategory)
        }
    }

    override suspend fun insert(category: Category) {
        val syncKey = CategorySyncKey.of(category.name)
        handler.await(inTransaction = true) {
            backfillCategorySyncKeys()
            val holder = categoriesQueries.getCategoryBySyncKey(syncKey, CategoryMapper::mapCategory)
                .awaitAsOneOrNull()
            when {
                holder?.deletedAt != null -> {
                    // The new values first, while the row is still deleted, so the version trigger
                    // stays quiet and the restore below logs the one change and bumps it once.
                    categoriesQueries.updateValues(
                        name = category.name,
                        order = category.order,
                        flags = category.flags,
                        parentId = category.parentId,
                        categoryId = holder.id,
                    )
                    // Joins this transaction; restore is the one write that clears a tombstone.
                    this@CategoryRepositoryImpl.restore(listOf(holder.id))
                }
                else -> categoriesQueries.insert(
                    name = category.name,
                    order = category.order,
                    flags = category.flags,
                    parentId = category.parentId,
                    syncKey = if (holder == null) syncKey else CategorySyncKey.random(),
                )
            }
        }
    }

    override suspend fun updateName(categoryId: Long, name: String) {
        handler.await(inTransaction = true) {
            backfillCategorySyncKeys()
            categoriesQueries.updateName(name = name, categoryId = categoryId)
        }
    }

    override suspend fun updateFlags(categoryId: Long, flags: Long) {
        handler.await { categoriesQueries.updateFlags(flags = flags, categoryId = categoryId) }
    }

    override suspend fun updateAllFlags(flags: Long?) {
        handler.await { categoriesQueries.updateAllFlags(flags = flags) }
    }

    override suspend fun updateAllOrders(orderedIds: List<Long>) {
        handler.await(inTransaction = true) {
            orderedIds.forEachIndexed { index, categoryId ->
                categoriesQueries.updateOrder(order = index.toLong(), categoryId = categoryId)
            }
        }
    }

    override suspend fun delete(categoryId: Long) {
        // The system category is undeletable and a BEFORE UPDATE trigger raises ABORT on it.
        // Refusing here keeps the transaction from opening at all.
        require(categoryId > Category.UNCATEGORIZED_ID) { "The system category cannot be deleted" }
        val now = System.currentTimeMillis()
        handler.await(inTransaction = true) {
            backfillCategorySyncKeys()
            categoriesQueries.reparentChildren(categoryId = categoryId)
            mangas_categoriesQueries.deleteByCategoryId(categoryId)
            categoriesQueries.softDelete(now = now, categoryId = categoryId)
        }
    }

    override suspend fun updateParent(categoryId: Long, parentId: Long?) {
        handler.await { categoriesQueries.updateParent(parentId = parentId, categoryId = categoryId) }
    }

    override suspend fun restore(categoryIds: List<Long>) {
        handler.await(inTransaction = true) {
            backfillCategorySyncKeys()
            categoriesQueries.restore(categoryIds)
        }
    }
}
