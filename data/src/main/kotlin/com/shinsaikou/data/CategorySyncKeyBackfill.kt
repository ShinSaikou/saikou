package com.shinsaikou.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.shinsaikou.domain.category.CategorySyncKey
import tachiyomi.data.Database
import tachiyomi.data.GetCategoriesWithoutSyncKey

/**
 * Keys every category the migration to schema version 2 left without one (phase 3 Q7), and finds
 * nothing once that has committed. The system category never gets a key.
 *
 * Rows whose names fold to one key share it by these rules: a live row takes the derived key
 * before a deleted one (the lowest id among live rows), the most recently deleted row takes it
 * when none is live, and every other row gets a random key. A key some row already holds is never
 * given again, so the unique index cannot fail here.
 *
 * [DatabaseInitializer]'s start-up transaction runs it, and so does every category write that
 * changes a name or a tombstone, inside its own transaction and before that change. The start-up
 * sweep runs on the application's scope, so a create, rename, delete or restore can reach the
 * database first; without this, a row created then would take a derived key the backfill owes to
 * an existing row, and the two devices would key one category differently.
 */
internal suspend fun Database.backfillCategorySyncKeys() {
    val unkeyed = categoriesQueries.getCategoriesWithoutSyncKey().awaitAsList()
    unkeyed.groupBy { CategorySyncKey.of(it.name) }.forEach { (syncKey, rows) ->
        val taken = categoriesQueries.getCategoryBySyncKey(syncKey).awaitAsOneOrNull() != null
        val keeper = if (taken) {
            null
        } else {
            rows.sortedWith(
                compareBy<GetCategoriesWithoutSyncKey>({ it.deleted_at != null }, { -(it.deleted_at ?: 0L) })
                    .thenBy { it._id },
            ).first()
        }
        rows.forEach { row ->
            val key = if (row == keeper) syncKey else CategorySyncKey.random()
            categoriesQueries.setSyncKey(syncKey = key, categoryId = row._id)
        }
    }
}
