// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/category/model/Category.kt (modified: yes)
package tachiyomi.domain.category.model

import java.io.Serializable

data class Category(
    val id: Long,
    val name: String,
    val order: Long,
    val flags: Long,
    /** Parent category, null = top level. 0 is never a legal parent. Fresh field, migration 1. */
    val parentId: Long? = null,
    /** Sync version counter. Fresh field, migration 1. */
    val version: Long = 0L,
    /** Epoch millis tombstone, null = live. Fresh field, migration 1. */
    val deletedAt: Long? = null,
    /**
     * The key two devices match the row by (phase 3 Q7), null for the system category and for a row
     * the start-up backfill has not keyed yet. Fresh field, read for the category tree's tie-break
     * (phase 5 unit CT); nothing writes through it.
     */
    val syncKey: String? = null,
) : Serializable {

    val isSystemCategory: Boolean = id == UNCATEGORIZED_ID

    companion object {
        const val UNCATEGORIZED_ID = 0L
    }
}
