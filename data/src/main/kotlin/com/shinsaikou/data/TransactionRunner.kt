package com.shinsaikou.data

import com.shinsaikou.domain.database.TransactionRunner
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * One database transaction spanning several repository calls, for interactors that need the whole
 * step to commit or roll back together (04-domain's chapter sync is the case that asks for it).
 * No repository interface exposes a transaction scope, so this is the only way to get one.
 *
 * The interface lives in `:domain`, so interactors can request atomic work without depending on
 * this module's SQLDelight implementation.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DatabaseTransactionRunner(
    private val handler: DatabaseHandler,
) : TransactionRunner {

    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        handler.await(inTransaction = true) { block() }
}
