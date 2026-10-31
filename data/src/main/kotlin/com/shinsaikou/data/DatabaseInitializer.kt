package com.shinsaikou.data

import app.cash.sqldelight.db.SqlDriver
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The startup sweep, called from `App.onCreate` once the graph exists (05-data section 1).
 *
 * `is_syncing` is a per-row latch that `:backup` and `:sync` raise around a write so the version
 * and change-log triggers stay quiet. A process death mid-restore leaves it raised, which would
 * silence those triggers forever, so every latch is lowered at start. The history sweep is the
 * safety net for a reset whose delete never committed; `HistoryRepositoryImpl` removes the rows
 * in the resetting transaction (decision E14). The categories' sync keys are backfilled in the same
 * transaction, after the driver's migration to schema version 2 (phase 3 unit C); see
 * [backfillCategorySyncKeys] for the category writes that may run it first.
 *
 * `PRAGMA optimize` is the only pragma set here: journal mode and foreign keys are decided when
 * the driver is built, in [DatabaseBindings].
 */
@Inject
@SingleIn(AppScope::class)
class DatabaseInitializer(
    private val handler: DatabaseHandler,
    private val driver: SqlDriver,
) {

    /** Runs on the handler's query dispatcher, so the caller may invoke it from any scope. */
    suspend fun initialize() {
        handler.await(inTransaction = true) {
            mangasQueries.resetIsSyncing()
            chaptersQueries.resetIsSyncing()
            categoriesQueries.resetIsSyncing()
            historyQueries.removeResettedHistory()
            backfillCategorySyncKeys()
        }
        driver.execute(identifier = null, sql = "PRAGMA optimize", parameters = 0).await()
    }
}
