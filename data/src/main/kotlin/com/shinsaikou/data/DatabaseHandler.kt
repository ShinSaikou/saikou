package com.shinsaikou.data

import app.cash.sqldelight.ExecutableQuery
import app.cash.sqldelight.Query
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.shinsaikou.domain.database.TransactionRunner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.data.subscribeToOne
import tachiyomi.data.subscribeToOneOrNull

/**
 * The single entry point to [Database]. Every repository goes through it, so the query dispatcher
 * and the transaction boundary are decided in one place rather than at each call site.
 *
 * The `await*` blocks take an [ExecutableQuery] rather than contract 05 section 1's [Query],
 * because `selectLastInsertedRowId` is an [ExecutableQuery]. [Query] is a subtype, so every call
 * site the contract describes still compiles.
 */
interface DatabaseHandler {

    /** Runs [block] on the query dispatcher, optionally inside a transaction. */
    suspend fun <T> await(inTransaction: Boolean = false, block: suspend Database.() -> T): T

    suspend fun <T : Any> awaitList(
        inTransaction: Boolean = false,
        block: suspend Database.() -> ExecutableQuery<T>,
    ): List<T>

    suspend fun <T : Any> awaitOne(
        inTransaction: Boolean = false,
        block: suspend Database.() -> ExecutableQuery<T>,
    ): T

    suspend fun <T : Any> awaitOneOrNull(
        inTransaction: Boolean = false,
        block: suspend Database.() -> ExecutableQuery<T>,
    ): T?

    /** Conflated flows that re-run [block] whenever one of the tables it reads changes. */
    fun <T : Any> subscribeToList(block: Database.() -> Query<T>): Flow<List<T>>

    fun <T : Any> subscribeToOne(block: Database.() -> Query<T>): Flow<T>

    fun <T : Any> subscribeToOneOrNull(block: Database.() -> Query<T>): Flow<T?>
}

/**
 * The one implementation. Nested [await] calls with `inTransaction = true` join the outer
 * transaction, so an interactor may wrap several repository calls through [TransactionRunner].
 */
class AndroidDatabaseHandler(
    private val db: Database,
    private val queryDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DatabaseHandler {

    override suspend fun <T> await(inTransaction: Boolean, block: suspend Database.() -> T): T =
        dispatch(inTransaction) { block(db) }

    override suspend fun <T : Any> awaitList(
        inTransaction: Boolean,
        block: suspend Database.() -> ExecutableQuery<T>,
    ): List<T> = dispatch(inTransaction) { block(db).awaitAsList() }

    override suspend fun <T : Any> awaitOne(
        inTransaction: Boolean,
        block: suspend Database.() -> ExecutableQuery<T>,
    ): T = dispatch(inTransaction) { block(db).awaitAsOne() }

    override suspend fun <T : Any> awaitOneOrNull(
        inTransaction: Boolean,
        block: suspend Database.() -> ExecutableQuery<T>,
    ): T? = dispatch(inTransaction) { block(db).awaitAsOneOrNull() }

    override fun <T : Any> subscribeToList(block: Database.() -> Query<T>): Flow<List<T>> =
        db.block().subscribeToList(queryDispatcher).conflate()

    override fun <T : Any> subscribeToOne(block: Database.() -> Query<T>): Flow<T> =
        db.block().subscribeToOne(queryDispatcher).conflate()

    override fun <T : Any> subscribeToOneOrNull(block: Database.() -> Query<T>): Flow<T?> =
        db.block().subscribeToOneOrNull(queryDispatcher).conflate()

    private suspend fun <T> dispatch(inTransaction: Boolean, block: suspend () -> T): T =
        withContext(queryDispatcher) {
            if (inTransaction) db.transactionWithResult { block() } else block()
        }
}
