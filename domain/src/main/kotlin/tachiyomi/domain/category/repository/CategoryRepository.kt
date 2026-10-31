// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/category/repository/CategoryRepository.kt (modified: yes)
package tachiyomi.domain.category.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.category.model.Category

interface CategoryRepository {

    suspend fun get(id: Long): Category?

    /** Live categories ORDER BY sort; includes the system category (id 0). */
    suspend fun getAll(): List<Category>

    fun getAllAsFlow(): Flow<List<Category>>

    suspend fun getCategoriesByMangaId(mangaId: Long): List<Category>

    fun getCategoriesByMangaIdAsFlow(mangaId: Long): Flow<List<Category>>

    /**
     * Keys the row with [com.shinsaikou.domain.category.CategorySyncKey.of] its name (phase 3 Q7). When a
     * deleted row already holds that key, the row is restored under the new name, order, flags and
     * parent instead of a second one being inserted. When a live row holds it (a category renamed
     * away from this name), the new row gets a random key.
     */
    suspend fun insert(category: Category)

    suspend fun updateName(categoryId: Long, name: String)

    suspend fun updateFlags(categoryId: Long, flags: Long)

    /** null flags = no-op (coalesce), as upstream. */
    suspend fun updateAllFlags(flags: Long?)

    /** orderedIds[i] gets sort = i, one transaction. */
    suspend fun updateAllOrders(orderedIds: List<Long>)

    /**
     * Soft delete: deleted_at = now and the category's mangas_categories rows removed, one
     * transaction. Refuses id <= 0.
     */
    suspend fun delete(categoryId: Long)

    // Fresh below this line.

    suspend fun updateParent(categoryId: Long, parentId: Long?)

    /** Clears the tombstone; a trigger stamps `restored_at` (phase 3 Q6), as for chapters. */
    suspend fun restore(categoryIds: List<Long>)
}
