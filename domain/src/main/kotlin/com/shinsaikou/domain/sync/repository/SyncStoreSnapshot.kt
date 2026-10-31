package com.shinsaikou.domain.sync.repository

/**
 * The library read at one state of the database with no writer kept waiting (phase 5 unit SC6):
 * what the library fingerprint reads (R101 item 3). A transaction through [com.shinsaikou.domain.database.TransactionRunner]
 * also reads one state, but it holds the database's one writer for as long as it runs, so every
 * write waits for it: about 45 s at 40,000 entries. `:data` implements this one with a connection
 * of its own in a read transaction, beside the app's writer and readers.
 */
interface SyncStoreSnapshot {

    /**
     * Runs [block] over a [SyncStore] whose every read sees the database as it stood when this call
     * began, in one read transaction that holds no write lock: a write made meanwhile, through any
     * other path, commits at once, and none of [block]'s reads sees it. The store only reads:
     * [SyncStore.beginExport] and [SyncStore.apply] fail on it, and it is not to be used after
     * [block] returns.
     */
    suspend fun <T> read(block: suspend (SyncStore) -> T): T
}
