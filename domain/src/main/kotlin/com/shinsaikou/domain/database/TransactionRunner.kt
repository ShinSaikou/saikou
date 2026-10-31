package com.shinsaikou.domain.database

/**
 * A transaction boundary that domain interactors can use without depending on a database module.
 * Repository implementations join this boundary when they execute their own database operations.
 */
interface TransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}
